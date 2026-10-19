package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.PlayerRole
import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.support.StreamInfra
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.PublishOptions
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The core writer as deployed (`PLAYER_ROLE=writer`, consumer on), fed through a real stream with
 * duplicates, out-of-order timestamps and an unreadable message. The database must end in the state
 * the contract promises regardless of the order or the number of deliveries.
 */
@QuarkusTest
@TestProfile(PlayerWriterIT.Writer::class)
@QuarkusTestResource(PlayerPostgresTestResource::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
class PlayerWriterIT {

    class Writer : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "quarkus.flyway.migrate-at-start" to "true",
                "grounds.player.role" to "writer",
                "grounds.player.events.consumer.enabled" to "true",
                "grounds.player.events.consumer.stream" to STREAM,
                "grounds.player.events.consumer.durable" to "player-writer-it",
            )
    }

    @Inject lateinit var consumer: PlayerEventConsumer

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var json: ObjectMapper

    @Inject lateinit var role: PlayerRole

    @Inject lateinit var registry: MeterRegistry

    private val t0: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

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
                    .subjects("player.>")
                    .storageType(StorageType.Memory)
                    .build()
            )
        }
    }

    @Test
    fun `the writer role is on`() {
        assertTrue(role.writer)
    }

    @Test
    fun `an older name sighting never overwrites a newer one, duplicates are dropped`() {
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()
        publish { nats ->
            nameSeen(nats, playerId, "NewName", t0.plusSeconds(10), "name-seen:$session")
            // The same message again, as a publisher retry: the stream drops it.
            nameSeen(nats, playerId, "NewName", t0.plusSeconds(10), "name-seen:$session")
            // An older sighting from an earlier session, delivered late.
            nameSeen(nats, playerId, "OldName", t0, "name-seen:${UUID.randomUUID()}")
        }
        assertEquals(2L, streamMessages())

        drain()
        assertEquals("NewName", storedName(playerId))
    }

    @Test
    fun `server changes apply newest-wins on their own session only`() {
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()
        insertSession(playerId, session)
        publish { nats ->
            serverChanged(nats, playerId, session, "game-2", t0.plusSeconds(5))
            serverChanged(nats, playerId, session, "game-1", t0.plusSeconds(1))
            // A redelivery under a different id (e.g. a sync fallback raced it): still a no-op.
            serverChanged(nats, playerId, session, "game-1", t0.plusSeconds(1), "retry")
            serverChanged(nats, playerId, UUID.randomUUID(), "stranger", t0.plusSeconds(9))
        }
        drain()
        assertEquals("game-2", serverName(playerId))
    }

    @Test
    fun `an ended session stays ended, and a late logout never removes a later session`() {
        val playerId = UUID.randomUUID()
        val first = UUID.randomUUID()
        insertSession(playerId, first)
        publish { nats ->
            ended(nats, playerId, first)
            ended(nats, playerId, first, "retry")
            serverChanged(nats, playerId, first, "late", t0.plusSeconds(3))
        }
        drain()
        assertEquals(0, sessionCount(playerId), "no resurrection by a late server change")

        val second = UUID.randomUUID()
        insertSession(playerId, second)
        publish { nats -> ended(nats, playerId, first, "late") }
        drain()
        assertEquals(1, sessionCount(playerId))
    }

    @Test
    fun `an unreadable message is terminated and does not hold the line`() {
        val playerId = UUID.randomUUID()
        val before = outcome("invalid")
        publish { nats ->
            nats.jetStream().publish("player.name.seen.$playerId", "not json".toByteArray())
            nats.jetStream().publish("player.unknown.$playerId", "{}".toByteArray())
            nameSeen(nats, playerId, "AfterGarbage", t0, "name-seen:${UUID.randomUUID()}")
        }
        drain()
        assertEquals("AfterGarbage", storedName(playerId))
        assertEquals(2.0, outcome("invalid") - before)
    }

    private fun outcome(outcome: String): Double =
        registry.find("player.events.consumer.messages").tag("outcome", outcome).counters().sumOf {
            it.count()
        }

    private fun drain() {
        repeat(3) { while (consumer.drain() > 0) Unit }
    }

    private fun publish(block: (Connection) -> Unit) {
        Nats.connect(StreamInfra.natsUrl()).use(block)
    }

    private fun send(nats: Connection, subject: String, id: String, body: Any) {
        nats
            .jetStream()
            .publish(
                subject,
                null,
                json.writeValueAsBytes(body),
                PublishOptions.builder().messageId(id).build(),
            )
    }

    private fun nameSeen(nats: Connection, playerId: UUID, name: String, at: Instant, id: String) =
        send(
            nats,
            PlayerEvents.nameSeenSubject(playerId),
            id,
            PlayerEvents.NameSeen(playerId.toString(), name, at.toString()),
        )

    private fun serverChanged(
        nats: Connection,
        playerId: UUID,
        session: UUID,
        server: String,
        at: Instant,
        idSuffix: String = "",
    ) =
        send(
            nats,
            PlayerEvents.serverChangedSubject(playerId),
            PlayerEvents.serverChangedId(session, at) + idSuffix,
            PlayerEvents.ServerChanged(
                playerId.toString(),
                session.toString(),
                server,
                at.toString(),
            ),
        )

    private fun ended(nats: Connection, playerId: UUID, session: UUID, idSuffix: String = "") =
        send(
            nats,
            PlayerEvents.sessionEndedSubject(playerId),
            PlayerEvents.sessionEndedId(session) + idSuffix,
            PlayerEvents.SessionEnded(playerId.toString(), session.toString(), t0.toString()),
        )

    private fun streamMessages(): Long =
        Nats.connect(StreamInfra.natsUrl()).use {
            it.jetStreamManagement().getStreamInfo(STREAM).streamState.msgCount
        }

    private fun insertSession(playerId: UUID, session: UUID) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO player_sessions (player_id, connected_at, last_seen_at, session_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (player_id) DO UPDATE
                    SET session_id = EXCLUDED.session_id, server_name = NULL, server_updated_at = NULL
                    """
                )
                .use {
                    val now = Timestamp.from(Instant.now())
                    it.setObject(1, playerId)
                    it.setTimestamp(2, now)
                    it.setTimestamp(3, now)
                    it.setObject(4, session)
                    it.executeUpdate()
                }
        }
    }

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

    private fun serverName(playerId: UUID): String? =
        query("SELECT server_name FROM player_sessions WHERE player_id = ?", playerId) {
            if (it.next()) it.getString(1) else null
        }

    private fun storedName(playerId: UUID): String? =
        query("SELECT player_name FROM player_names WHERE player_id = ?", playerId) {
            if (it.next()) it.getString(1) else null
        }

    private companion object {
        const val STREAM = "PLAYER_WRITER_IT"
    }
}
