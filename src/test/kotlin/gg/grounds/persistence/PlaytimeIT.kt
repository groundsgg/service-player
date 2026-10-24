package gg.grounds.persistence

import gg.grounds.domain.PlayerSession
import gg.grounds.persistence.PlayerSessionRepository.ClaimSessionResult
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
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
import javax.sql.DataSource
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Playtime against Postgres, where it is kept: the V11 trigger credits a session's segment whenever
 * its server changes, its connection is replaced or the row goes away, and [findPlaytime] adds the
 * segment still running.
 *
 * Segments are aged by writing `server_since` and `last_seen_at` directly — the trigger only
 * touches `server_since` when a segment closes, so a plain UPDATE of it is a clock in the past.
 */
@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class PlaytimeIT {
    @Inject lateinit var repository: PlayerSessionRepository

    @Inject lateinit var dataSource: DataSource

    private val ttl = Duration.ofSeconds(90)

    @Test
    fun `server changes and logout credit total time, and game servers play time`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val now = Instant.now()
        assertEquals(ClaimSessionResult.Claimed, claim(playerId, sessionId, now))

        age(playerId, Duration.ofSeconds(60))
        repository.updateServerForSession(playerId, sessionId, "lobby-7b2kq-x9z")
        age(playerId, Duration.ofSeconds(600))
        repository.updateServerForSession(playerId, sessionId, "bedwars-2x1-abcde")
        age(playerId, Duration.ofSeconds(300))
        assertEquals(
            DeleteSessionResult.REMOVED,
            repository.deleteSessionForSession(playerId, sessionId),
        )

        assertPlaytime(total = 960, play = 300, repository.findPlaytime(playerId, ttl)!!)
    }

    @Test
    fun `playtime includes the running segment, and a lobby or the buildserver is not play time`() {
        val onGame = UUID.randomUUID()
        val inLobby = UUID.randomUUID()
        val building = UUID.randomUUID()
        insert(onGame, "duel-4kq2p", connectedAgo = 120, heartbeatAgo = 5)
        insert(inLobby, "lobby-7b2kq-x9z", connectedAgo = 120, heartbeatAgo = 5)
        insert(building, "buildserver-0", connectedAgo = 120, heartbeatAgo = 5)

        assertPlaytime(total = 120, play = 120, repository.findPlaytime(onGame, ttl)!!)
        assertPlaytime(total = 120, play = 0, repository.findPlaytime(inLobby, ttl)!!)
        assertPlaytime(total = 120, play = 0, repository.findPlaytime(building, ttl)!!)
    }

    @Test
    fun `a player never seen has no playtime`() {
        assertPlaytime(total = 0, play = 0, repository.findPlaytime(UUID.randomUUID(), ttl)!!)
    }

    @Test
    fun `a crashed proxy's players stop earning time one TTL after their last heartbeat`() {
        val playerId = UUID.randomUUID()
        insert(playerId, "getdown-9xk2l", connectedAgo = 3600, heartbeatAgo = 3000)

        // 600 s of heartbeats plus the 90 s the session stays valid after the last one.
        assertPlaytime(total = 690, play = 690, repository.findPlaytime(playerId, ttl)!!)

        repository.deleteStaleSessions(Instant.now().minus(ttl))
        assertPlaytime(total = 690, play = 690, repository.findPlaytime(playerId, ttl)!!)
    }

    @Test
    fun `a takeover of a stale session credits the session it replaces`() {
        val playerId = UUID.randomUUID()
        insert(playerId, "keystone-2x1-q8z1", connectedAgo = 1000, heartbeatAgo = 400)

        val now = Instant.now()
        assertEquals(ClaimSessionResult.Claimed, claim(playerId, UUID.randomUUID(), now))

        // The old session: 600 s of heartbeats plus the TTL. The new one has only just begun.
        assertPlaytime(total = 690, play = 690, repository.findPlaytime(playerId, ttl)!!)
    }

    @Test
    fun `countOnline counts the given players who hold a session`() {
        val online = List(2) { UUID.randomUUID() }
        online.forEach { insert(it, "lobby-1", connectedAgo = 10, heartbeatAgo = 1) }

        assertEquals(2, repository.countOnline(online + UUID.randomUUID()))
        assertEquals(0, repository.countOnline(listOf(UUID.randomUUID())))
    }

    private fun claim(playerId: UUID, sessionId: UUID, at: Instant) =
        repository.claimSession(
            PlayerSession(
                playerId = playerId,
                connectedAt = at,
                lastSeenAt = at,
                playerName = "Timer",
                proxyId = "velocity-1",
                sessionId = sessionId,
            ),
            at.minus(ttl),
        )

    /**
     * Moves the running segment's start [by] into the past, keeping the session heartbeat-fresh.
     */
    private fun age(playerId: UUID, by: Duration) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_sessions SET server_since = server_since - ?::bigint * INTERVAL '1 millisecond', last_seen_at = now() WHERE player_id = ?"
                )
                .use {
                    it.setLong(1, by.toMillis())
                    it.setObject(2, playerId)
                    it.executeUpdate()
                }
        }
    }

    private fun insert(playerId: UUID, server: String, connectedAgo: Long, heartbeatAgo: Long) {
        val now = Instant.now()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO player_sessions (player_id, connected_at, last_seen_at, server_name) VALUES (?, ?, ?, ?)"
                )
                .use {
                    it.setObject(1, playerId)
                    it.setTimestamp(2, Timestamp.from(now.minusSeconds(connectedAgo)))
                    it.setTimestamp(3, Timestamp.from(now.minusSeconds(heartbeatAgo)))
                    it.setString(4, server)
                    it.executeUpdate()
                }
        }
    }

    /** Seconds, within two: the database's clock and this one's tick apart while the test runs. */
    private fun assertPlaytime(total: Long, play: Long, actual: PlayerSessionRepository.Playtime) {
        assertTrue(abs(actual.totalMillis - total * 1000) < 2000, "total was $actual")
        assertTrue(abs(actual.playMillis - play * 1000) < 2000, "play was $actual")
    }
}
