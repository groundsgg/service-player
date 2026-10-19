package gg.grounds.persistence

import gg.grounds.domain.PlayerSession
import gg.grounds.persistence.PlayerSessionRepository.ClaimSessionResult
import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.presence.PlayerSessionPostgresTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The claim used with queued writes, against Postgres: still one statement, still exactly one
 * winner under concurrency, takeover only of a stale session or one whose logout is queued.
 */
@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class ClaimSessionAsyncIT {
    @Inject lateinit var repository: PlayerSessionRepository

    @Inject lateinit var dataSource: DataSource

    private val ttl = Duration.ofSeconds(90)

    @Test
    fun `concurrent claims for one profile admit exactly one session`() {
        val playerId = UUID.randomUUID()
        val contenders = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(contenders)
        val results =
            (1..contenders).map {
                pool.submit<ClaimSessionResult> {
                    start.await()
                    val now = Instant.now()
                    repository.claimSessionAsync(
                        session(playerId, UUID.randomUUID(), now),
                        now.minus(ttl),
                        emptyList(),
                    )
                }
            }
        start.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, outcomes.count { it == ClaimSessionResult.Claimed })
        assertEquals(contenders - 1, outcomes.count { it == ClaimSessionResult.AlreadyOnline })
    }

    @Test
    fun `a stale session is taken over and its server bookkeeping restarts`() {
        val playerId = UUID.randomUUID()
        val old = UUID.randomUUID()
        val now = Instant.now()
        claim(playerId, old, now.minusSeconds(600))
        setServer(playerId, "game-1", now.minusSeconds(590))

        val fresh = UUID.randomUUID()
        assertEquals(ClaimSessionResult.Claimed, claim(playerId, fresh, now))
        assertEquals(fresh, column<UUID>(playerId, "session_id"))
        assertNull(column<java.time.OffsetDateTime>(playerId, "server_updated_at"))
    }

    @Test
    fun `a fresh session is only taken over when its logout is queued`() {
        val playerId = UUID.randomUUID()
        val current = UUID.randomUUID()
        val now = Instant.now()
        claim(playerId, current, now)

        assertEquals(
            ClaimSessionResult.AlreadyOnline,
            claim(playerId, UUID.randomUUID(), now, ended = listOf(UUID.randomUUID())),
        )
        val next = UUID.randomUUID()
        assertEquals(
            ClaimSessionResult.Claimed,
            claim(playerId, next, now, ended = listOf(current)),
        )
        assertEquals(next, column<UUID>(playerId, "session_id"))
    }

    @Test
    fun `a retried claim of the same session keeps its connection time`() {
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()
        val first = Instant.now().minusSeconds(5)
        claim(playerId, session, first)
        assertEquals(ClaimSessionResult.Claimed, claim(playerId, session, Instant.now()))
        val connectedAt = column<java.time.OffsetDateTime>(playerId, "connected_at")!!.toInstant()
        assertEquals(first.toEpochMilli(), connectedAt.toEpochMilli())
    }

    private fun claim(
        playerId: UUID,
        sessionId: UUID,
        at: Instant,
        ended: List<UUID> = emptyList(),
    ): ClaimSessionResult =
        repository.claimSessionAsync(session(playerId, sessionId, at), at.minus(ttl), ended)

    private fun session(playerId: UUID, sessionId: UUID, at: Instant) =
        PlayerSession(
            playerId = playerId,
            connectedAt = at,
            lastSeenAt = at,
            playerName = "Racer",
            proxyId = "velocity-1",
            sessionId = sessionId,
        )

    private fun setServer(playerId: UUID, server: String, at: Instant) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_sessions SET server_name = ?, server_updated_at = ? WHERE player_id = ?"
                )
                .use {
                    it.setString(1, server)
                    it.setTimestamp(2, Timestamp.from(at))
                    it.setObject(3, playerId)
                    it.executeUpdate()
                }
        }
    }

    private inline fun <reified T> column(playerId: UUID, column: String): T? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT $column FROM player_sessions WHERE player_id = ?")
                .use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use {
                        if (it.next()) it.getObject(1, T::class.java) else null
                    }
                }
        }
}
