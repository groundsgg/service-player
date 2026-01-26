package gg.grounds.persistence

import gg.grounds.domain.PlayerSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerSessionRepository @Inject constructor(private val dataSource: DataSource) {
    enum class DeleteSessionResult {
        REMOVED,
        NOT_FOUND,
        ERROR,
    }

    fun insertSession(session: PlayerSession): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_SESSION).use { statement ->
                    statement.setObject(1, session.playerId)
                    statement.setTimestamp(2, Timestamp.from(session.connectedAt))
                    statement.setTimestamp(3, Timestamp.from(session.lastSeenAt))
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session insert failed (playerId=%s, reason=sql_error)",
                session.playerId,
            )
            false
        }
    }

    fun findByPlayerId(playerId: UUID): PlayerSession? {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) mapSession(resultSet) else null
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session fetch failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            null
        }
    }

    fun deleteSession(playerId: UUID): DeleteSessionResult {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    if (statement.executeUpdate() > 0) DeleteSessionResult.REMOVED
                    else DeleteSessionResult.NOT_FOUND
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session delete failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            DeleteSessionResult.ERROR
        }
    }

    fun touchSessions(playerIds: Collection<UUID>, lastSeenAt: Instant): Int {
        if (playerIds.isEmpty()) {
            return 0
        }

        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_LAST_SEEN_BATCH).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(lastSeenAt))
                    val array = connection.createArrayOf("uuid", playerIds.toTypedArray())
                    statement.setArray(2, array)
                    statement.executeUpdate()
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session batch update failed (count=%d, reason=sql_error)",
                playerIds.size,
            )
            0
        }
    }

    fun deleteStaleSessions(cutoff: Instant): Int {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_STALE).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(cutoff))
                    statement.executeUpdate()
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Stale player session cleanup failed (cutoff=%s, reason=sql_error)",
                cutoff,
            )
            0
        }
    }

    private fun mapSession(resultSet: ResultSet): PlayerSession {
        val playerId =
            requireNotNull(resultSet.getObject("player_id", UUID::class.java)) {
                "player_id is null"
            }
        val connectedAt =
            requireNotNull(resultSet.getTimestamp("connected_at")) { "connected_at is null" }
                .toInstant()
        val lastSeenAt =
            requireNotNull(resultSet.getTimestamp("last_seen_at")) { "last_seen_at is null" }
                .toInstant()
        return PlayerSession(playerId, connectedAt, lastSeenAt)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionRepository::class.java)

        private const val INSERT_SESSION =
            """
            INSERT INTO player_sessions (player_id, connected_at, last_seen_at)
            VALUES (?, ?, ?)
            ON CONFLICT (player_id) DO NOTHING
            """
        private const val SELECT_BY_PLAYER =
            """
            SELECT player_id, connected_at, last_seen_at
            FROM player_sessions
            WHERE player_id = ?
            """
        private const val DELETE_BY_PLAYER =
            """
            DELETE FROM player_sessions
            WHERE player_id = ?
            """
        private const val UPDATE_LAST_SEEN_BATCH =
            """
            UPDATE player_sessions
            SET last_seen_at = ?
            WHERE player_id = ANY(?)
            """
        private const val DELETE_STALE =
            """
            DELETE FROM player_sessions
            WHERE last_seen_at < ?
            """
    }
}
