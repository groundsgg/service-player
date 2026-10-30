package gg.grounds.events

import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.support.StreamInfra
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Nats
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The regional runtime with queued writes and logouts on, against a real JetStream buffer, and the
 * writer draining the same stream. Covers what the flags change for a player: the claim verdict
 * stays synchronous, the reconnect right after a queued logout is admitted (ended-fence), a
 * stranger session is still refused, late events never touch a later session, and a missing buffer
 * falls back to applying synchronously.
 */
@QuarkusTest
@TestProfile(AsyncWritesIT.Async::class)
@QuarkusTestResource(PlayerPostgresTestResource::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
class AsyncWritesIT {

    class Async : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "quarkus.flyway.migrate-at-start" to "true",
                "grounds.player.async-writes" to "true",
                "grounds.player.async-logout" to "true",
                "grounds.player.events.consumer.stream" to STREAM,
                "grounds.player.events.consumer.durable" to "player-writer-async-it",
            )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var consumer: PlayerEventConsumer

    @Inject lateinit var registry: MeterRegistry

    /** Streams may not overlap in subjects, and another test class brings its own. */
    @AfterEach
    fun dropStream() {
        Nats.connect(StreamInfra.natsUrl()).use {
            runCatching { it.jetStreamManagement().deleteStream(STREAM) }
        }
    }

    @BeforeEach
    fun freshStream() {
        Nats.connect(StreamInfra.natsUrl()).use { nats ->
            val jsm = nats.jetStreamManagement()
            runCatching { jsm.deleteStream(STREAM) }
            jsm.addStream(
                StreamConfiguration.builder()
                    .name(STREAM)
                    .subjects(
                        "player.name.seen.>",
                        "player.session.server-changed.>",
                        "player.session.ended.>",
                    )
                    .storageType(StorageType.Memory)
                    .build()
            )
        }
    }

    @Test
    fun `a claim stays a synchronous verdict and queues the name for the writer`() {
        val playerId = UUID.randomUUID()
        claim(playerId, UUID.randomUUID(), "Queued").then().statusCode(201)

        assertEquals(1, sessionCount(playerId))
        assertNull(storedName(playerId))
        assertEquals(1L, streamMessages())

        drain()
        assertEquals("Queued", storedName(playerId))
    }

    @Test
    fun `a reconnect right after a queued logout is admitted before the writer ran`() {
        val playerId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        claim(playerId, first).then().statusCode(201)

        logout(playerId, first).then().statusCode(204)
        assertEquals(first, sessionId(playerId), "the logout is queued, the row is still there")

        claim(playerId, second).then().statusCode(201)
        assertEquals(second, sessionId(playerId))

        drain()
        assertEquals(second, sessionId(playerId), "the late logout of the first session is a no-op")
    }

    @Test
    fun `queued logout cannot let a tokenless reconnect supersede a pending handoff`() {
        val playerId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val destination = UUID.randomUUID()
        claim(playerId, source).then().statusCode(201)
        val token =
            given()
                .contentType(ContentType.JSON)
                .body(mapOf("sessionId" to source.toString()))
                .post("/v2/players/$playerId/session/transfer")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("token")

        logout(playerId, source).then().statusCode(204)
        claim(playerId, UUID.randomUUID()).then().statusCode(409)
        drain()
        assertEquals(source, sessionId(playerId))

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to destination.toString(),
                    "handoffToken" to token,
                    "proxyId" to "velocity-2",
                )
            )
            .post("/v2/players/sessions/transfer")
            .then()
            .statusCode(201)
        drain()
        assertEquals(destination, sessionId(playerId))
    }

    @Test
    fun `a session nobody logged out still makes a second claim already online`() {
        val playerId = UUID.randomUUID()
        claim(playerId, UUID.randomUUID()).then().statusCode(201)
        claim(playerId, UUID.randomUUID()).then().statusCode(409)
    }

    @Test
    fun `a queued logout of the current session removes it once the writer ran`() {
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()
        claim(playerId, session).then().statusCode(201)
        logout(playerId, session).then().statusCode(204)
        drain()
        assertEquals(0, sessionCount(playerId))
    }

    @Test
    fun `server changes are queued and a late one never lands on a later session`() {
        val playerId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        claim(playerId, first).then().statusCode(201)
        server(playerId, first, "lobby-1").then().statusCode(204)
        assertNull(serverName(playerId))

        drain()
        assertEquals("lobby-1", serverName(playerId))

        logout(playerId, first).then().statusCode(204)
        claim(playerId, second).then().statusCode(201)
        server(playerId, first, "late-game").then().statusCode(204)
        drain()
        assertEquals(second, sessionId(playerId))
        assertNull(serverName(playerId), "the first session's late change is not the second's")
    }

    @Test
    fun `without a buffer every write is applied synchronously`() {
        Nats.connect(StreamInfra.natsUrl()).use { it.jetStreamManagement().deleteStream(STREAM) }
        val before = fallbacks()
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()

        claim(playerId, session, "Direct").then().statusCode(201)
        assertEquals("Direct", storedName(playerId))

        server(playerId, session, "lobby-2").then().statusCode(204)
        assertEquals("lobby-2", serverName(playerId))
        server(playerId, UUID.randomUUID(), "lobby-3").then().statusCode(404)

        logout(playerId, UUID.randomUUID()).then().statusCode(404)
        logout(playerId, session).then().statusCode(204)
        assertEquals(0, sessionCount(playerId))

        assertTrue(fallbacks() - before >= 5.0, "every write took the fallback")
    }

    /**
     * Each test recreates the stream, which takes the durable with it; the first idle drain notices
     * and the next one resubscribes. So drain until a few passes in a row found nothing.
     */
    private fun drain() {
        repeat(3) { while (consumer.drain() > 0) Unit }
    }

    private fun fallbacks(): Double =
        registry.find("player.writes").tag("path", "sync_fallback").counters().sumOf {
            it.count()
        }

    private fun streamMessages(): Long =
        Nats.connect(StreamInfra.natsUrl()).use {
            it.jetStreamManagement().getStreamInfo(STREAM).streamState.msgCount
        }

    private fun claim(playerId: UUID, sessionId: UUID, name: String = "Player"): Response =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to sessionId.toString(),
                    "playerName" to name,
                    "proxyId" to "velocity-1",
                )
            )
            .post("/v2/players/sessions")

    private fun logout(playerId: UUID, sessionId: UUID): Response =
        given().delete("/v2/players/$playerId/session?sessionId=$sessionId")

    private fun server(playerId: UUID, sessionId: UUID, server: String): Response =
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to sessionId.toString(), "serverName" to server))
            .put("/v2/players/$playerId/session/server")

    private fun <T> query(sql: String, playerId: UUID, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, playerId)
                statement.executeQuery().use(read)
            }
        }

    private fun sessionCount(playerId: UUID): Int =
        query("SELECT count(*) FROM player_sessions WHERE player_id = ?", playerId) {
            it.next()
            it.getInt(1)
        }

    private fun sessionId(playerId: UUID): UUID? =
        query("SELECT session_id FROM player_sessions WHERE player_id = ?", playerId) {
            if (it.next()) it.getObject(1, UUID::class.java) else null
        }

    private fun serverName(playerId: UUID): String? =
        query("SELECT server_name FROM player_sessions WHERE player_id = ?", playerId) {
            if (it.next()) it.getString(1) else null
        }

    private fun storedName(playerId: UUID): String? =
        query("SELECT player_name FROM player_names WHERE player_id = ?", playerId) {
            if (it.next()) it.getString(1) else null
        }

    private companion object {
        const val STREAM = "PLAYER_ASYNC_IT"
    }
}
