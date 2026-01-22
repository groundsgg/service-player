package gg.grounds.persistence

import gg.grounds.domain.PlayerSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
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
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to insert player session (playerId=%s)", session.playerId)
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
            LOG.errorf(error, "Failed to fetch player session (playerId=%s)", playerId)
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
            LOG.errorf(error, "Failed to delete player session (playerId=%s)", playerId)
            DeleteSessionResult.ERROR
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
        return PlayerSession(playerId, connectedAt)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionRepository::class.java)

        private const val INSERT_SESSION =
            """
            INSERT INTO player_sessions (player_id, connected_at)
            VALUES (?, ?)
            ON CONFLICT (player_id) DO NOTHING
            """
        private const val SELECT_BY_PLAYER =
            """
            SELECT player_id, connected_at
            FROM player_sessions
            WHERE player_id = ?
            """
        private const val DELETE_BY_PLAYER =
            """
            DELETE FROM player_sessions
            WHERE player_id = ?
            """
    }
}
