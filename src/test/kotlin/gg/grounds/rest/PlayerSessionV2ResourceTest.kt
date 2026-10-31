package gg.grounds.rest

import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.presence.PlayerSessionPostgresTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class PlayerSessionV2ResourceTest {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var sessions: PlayerSessionRepository

    @Test
    fun `source disconnect before destination login preserves the issued handoff`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        given().delete("/v2/players/$playerId/session?sessionId=$sourceSession")
        claim(playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
        consume(token, playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(404)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-2"))
    }

    @Test
    fun `queued source disconnect before destination login preserves the issued handoff`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        sessions.applySessionEnded(playerId, sourceSession)

        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
    }

    @Test
    fun `expired unconsumed handoff does not prevent logout`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeUpdate()
                }
        }

        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(204)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
        given().get("/v1/players/$playerId/session").then().statusCode(404)
    }

    @Test
    fun `separate profile uuids can both be admitted`() {
        val javaProfile = UUID.randomUUID()
        val bedrockProfile = UUID.randomUUID()

        claim(javaProfile, UUID.randomUUID(), "velocity-java").then().statusCode(201)
        claim(bedrockProfile, UUID.randomUUID(), "velocity-bedrock").then().statusCode(201)
    }

    @Test
    fun `same profile only has one active session even on a different proxy`() {
        val playerId = UUID.randomUUID()
        val firstSession = UUID.randomUUID()

        claim(playerId, firstSession, "velocity-1")
            .then()
            .statusCode(201)
            .body("playerId", equalTo(playerId.toString()))
            .body("sessionId", equalTo(firstSession.toString()))
            .body("leaseExpiresAt", org.hamcrest.Matchers.notNullValue())

        claim(playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
    }

    @Test
    fun `retry of the same claim is idempotent and older session cleanup cannot release it`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        claim(playerId, UUID.randomUUID(), "velocity-1").then().statusCode(409)

        given()
            .delete("/v2/players/$playerId/session?sessionId=${UUID.randomUUID()}")
            .then()
            .statusCode(404)
        given().get("/v1/players/$playerId/session").then().statusCode(200)
    }

    @Test
    fun `legacy v1 login logout and heartbeat cannot mutate v2 ownership`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerId" to playerId.toString(), "proxyId" to "velocity-2"))
            .post("/v1/players/sessions")
            .then()
            .statusCode(409)

        given().delete("/v1/players/$playerId/session").then().statusCode(404)
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerIds" to listOf(playerId.toString())))
            .post("/v1/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(0))

        given().get("/v1/players/$playerId/session").then().statusCode(200)
    }

    @Test
    fun `single-use handoff moves ownership only for the bound profile`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        val destinationSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)

        val token =
            given()
                .contentType(ContentType.JSON)
                .body(mapOf("sessionId" to sourceSession.toString()))
                .post("/v2/players/$playerId/session/transfer")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("token")

        consume(token, playerId, destinationSession, "velocity-2").then().statusCode(201)
        consume(token, playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(404)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-2"))
    }

    @Test
    fun `wrong profile cannot consume a handoff token`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        consume(token, UUID.randomUUID(), UUID.randomUUID(), "velocity-2").then().statusCode(409)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
    }

    @Test
    fun `heartbeat server update and logout require the exact session id`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "sessions" to
                        listOf(
                            mapOf(
                                "playerId" to playerId.toString(),
                                "sessionId" to UUID.randomUUID().toString(),
                            )
                        )
                )
            )
            .post("/v2/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(0))
            .body("missing", equalTo(1))

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "sessions" to
                        listOf(
                            mapOf(
                                "playerId" to playerId.toString(),
                                "sessionId" to sessionId.toString(),
                            )
                        )
                )
            )
            .post("/v2/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(1))
            .body("missing", equalTo(0))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to UUID.randomUUID().toString(), "serverName" to "lobby-2"))
            .put("/v2/players/$playerId/session/server")
            .then()
            .statusCode(404)
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to sessionId.toString(), "serverName" to "lobby-2"))
            .put("/v2/players/$playerId/session/server")
            .then()
            .statusCode(204)

        given()
            .delete("/v2/players/$playerId/session?sessionId=${UUID.randomUUID()}")
            .then()
            .statusCode(404)
        given().get("/v1/players/$playerId/session").then().statusCode(200)
        given().delete("/v2/players/$playerId/session?sessionId=$sessionId").then().statusCode(204)
        given().get("/v1/players/$playerId/session").then().statusCode(404)
    }

    @Test
    fun `expired handoff cannot release or replace the current session`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sessionId)

        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeUpdate()
                }
        }

        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-1"))
    }

    @Test
    fun `concurrent claims for one profile have exactly one winner`() {
        val playerId = UUID.randomUUID()
        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val requests =
            (1..16).map {
                Thread {
                    statuses.add(
                        claim(playerId, UUID.randomUUID(), "velocity-$it")
                            .then()
                            .extract()
                            .statusCode()
                    )
                }
            }
        requests.forEach(Thread::start)
        requests.forEach { it.join() }
        assertEquals(16, statuses.size)
        assertEquals(1, statuses.count { it == 201 })
        assertEquals(15, statuses.count { it == 409 })
    }

    private fun claim(playerId: UUID, sessionId: UUID, proxyId: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to sessionId.toString(),
                    "playerName" to "Player${playerId.toString().take(8)}",
                    "proxyId" to proxyId,
                    "region" to "eu",
                )
            )
            .post("/v2/players/sessions")

    @Test
    fun `admission binds a ticket to one destination connection and blocks legacy consume`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val destination = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val token = issueHandoff(player, source)
        val expiry =
            admit(token, player, destination, "target-a")
                .then()
                .statusCode(201)
                .body("connectionId", equalTo(destination.toString()))
                .extract()
                .path<String>("expiresAt")
        admit(token, player, destination, "target-a")
            .then()
            .statusCode(201)
            .body("expiresAt", equalTo(expiry))
        admit(token, player, UUID.randomUUID(), "target-b").then().statusCode(409)
        admit(token, UUID.randomUUID(), destination, "target-a").then().statusCode(409)
        consume(token, player, destination, "target-a").then().statusCode(409)
        consumeBound(token, player, destination, "target-b").then().statusCode(409)
        consumeBound(token, player, destination, "target-a").then().statusCode(201)
        admit(token, player, destination, "target-a").then().statusCode(409)
    }

    @Test
    fun `two tokens of one source session cannot admit two connections`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val first = issueHandoff(player, source)
        val second = issueHandoff(player, source)
        val connection = UUID.randomUUID()
        admit(first, player, connection, "target-a").then().statusCode(201)
        admit(second, player, UUID.randomUUID(), "target-b").then().statusCode(409)
        consume(second, player, UUID.randomUUID(), "legacy-target").then().statusCode(409)
        given().delete("/v2/players/$player/session?sessionId=$source")
        consumeBound(first, player, connection, "target-a").then().statusCode(201)
    }

    @Test
    fun `expired admission cannot be renewed or consumed`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val connection = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val token = issueHandoff(player, source)
        admit(token, player, connection, "target").then().statusCode(201)
        dataSource.connection.use { db ->
            db.prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use {
                    it.setObject(1, player)
                    it.executeUpdate()
                }
        }
        admit(token, player, connection, "target").then().statusCode(409)
        consumeBound(token, player, connection, "target").then().statusCode(409)
    }

    @Test
    fun `concurrent admission on different proxies has one winner`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val tokens = listOf(issueHandoff(player, source), issueHandoff(player, source))
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks = tokens.mapIndexed { index, token ->
                pool.submit<Int> {
                    start.await()
                    admit(token, player, UUID.randomUUID(), "target-$index").statusCode
                }
            }
            start.countDown()
            assertEquals(listOf(201, 409), tasks.map { it.get() }.sorted())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `handoff issuance applies one shared outstanding budget across source proxies`() {
        // Isolated PostgreSQL fixture: seed all budget slots with active source tickets.
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        claim(player, source, "source-budget").then().statusCode(201)
        dataSource.connection.use { db ->
            db.prepareStatement(
                    "INSERT INTO player_session_handoffs(token_hash, player_id, source_session_id, expires_at) SELECT decode(md5(random()::text), 'hex'), ?, ?, NOW() + INTERVAL '15 seconds' FROM generate_series(1,32)"
                )
                .use {
                    it.setObject(1, player)
                    it.setObject(2, source)
                    it.executeUpdate()
                }
        }
        try {
            given()
                .contentType(ContentType.JSON)
                .body(mapOf("sessionId" to source.toString()))
                .post("/v2/players/$player/session/transfer")
                .then()
                .statusCode(409)
        } finally {
            dataSource.connection.use { db ->
                db.prepareStatement("DELETE FROM player_session_handoffs WHERE player_id = ?").use {
                    it.setObject(1, player)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `aborted admission cannot be consumed or rebound and preserves the source`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val connection = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val token = issueHandoff(player, source)
        admit(token, player, connection, "target").then().statusCode(201)
        abortAdmission(token, player, connection, "target").then().statusCode(204)
        abortAdmission(token, player, connection, "target").then().statusCode(204)
        admit(token, player, connection, "target").then().statusCode(409)
        admit(token, player, UUID.randomUUID(), "other").then().statusCode(409)
        consumeBound(token, player, connection, "target").then().statusCode(409)
        given()
            .get("/v1/players/$player/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("source"))
    }

    @Test
    fun `abort cleans a committed destination but never a later connection`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val connection = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val token = issueHandoff(player, source)
        admit(token, player, connection, "target").then().statusCode(201)
        consumeBound(token, player, connection, "target").then().statusCode(201)
        abortAdmission(token, player, connection, "target").then().statusCode(204)
        given().get("/v1/players/$player/session").then().statusCode(404)
        claim(player, UUID.randomUUID(), "later").then().statusCode(201)
        abortAdmission(token, player, connection, "target").then().statusCode(204)
        given()
            .get("/v1/players/$player/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("later"))
    }

    @Test
    fun `concurrent abort and consume leaves no orphaned destination`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val connection = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val token = issueHandoff(player, source)
        admit(token, player, connection, "target").then().statusCode(201)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val abort =
                pool.submit<Int> {
                    start.await()
                    abortAdmission(token, player, connection, "target").statusCode
                }
            val consume =
                pool.submit<Int> {
                    start.await()
                    consumeBound(token, player, connection, "target").statusCode
                }
            start.countDown()
            assertEquals(204, abort.get())
            val status = consume.get()
            org.junit.jupiter.api.Assertions.assertTrue(status == 201 || status == 409)
            val current = given().get("/v1/players/$player/session")
            org.junit.jupiter.api.Assertions.assertTrue(
                current.statusCode == 404 || current.jsonPath().getString("proxyId") == "source"
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a fresh source ticket after expiry never reopens the expired connection`() {
        val player = UUID.randomUUID()
        val source = UUID.randomUUID()
        val connection = UUID.randomUUID()
        claim(player, source, "source").then().statusCode(201)
        val first = issueHandoff(player, source)
        admit(first, player, connection, "target").then().statusCode(201)
        dataSource.connection.use { db ->
            db.prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use {
                    it.setObject(1, player)
                    it.executeUpdate()
                }
        }
        val fresh = issueHandoff(player, source)
        admit(fresh, player, UUID.randomUUID(), "other").then().statusCode(201)
        admit(first, player, connection, "target").then().statusCode(409)
        consumeBound(first, player, connection, "target").then().statusCode(409)
    }

    private fun abortAdmission(token: String, player: UUID, connection: UUID, proxy: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to player.toString(),
                    "handoffToken" to token,
                    "connectionId" to connection.toString(),
                    "destinationProxyId" to proxy,
                )
            )
            .post("/v2/players/sessions/transfer/admission/abort")

    private fun admit(token: String, player: UUID, connection: UUID, proxy: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to player.toString(),
                    "handoffToken" to token,
                    "connectionId" to connection.toString(),
                    "destinationProxyId" to proxy,
                )
            )
            .post("/v2/players/sessions/transfer/admission")

    private fun consumeBound(token: String, player: UUID, connection: UUID, proxy: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to player.toString(),
                    "handoffToken" to token,
                    "sessionId" to connection.toString(),
                    "connectionId" to connection.toString(),
                    "proxyId" to proxy,
                )
            )
            .post("/v2/players/sessions/transfer")

    private fun issueHandoff(playerId: UUID, sessionId: UUID): String =
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to sessionId.toString()))
            .post("/v2/players/$playerId/session/transfer")
            .then()
            .statusCode(201)
            .extract()
            .path("token")

    private fun consume(token: String, playerId: UUID, sessionId: UUID, proxyId: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to sessionId.toString(),
                    "handoffToken" to token,
                    "proxyId" to proxyId,
                    "region" to "eu",
                )
            )
            .post("/v2/players/sessions/transfer")
}
