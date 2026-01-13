package gg.grounds.persistence

import gg.grounds.domain.PlayerSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PlayerSessionRepository {
    @Inject
    lateinit var dataSource: DataSource

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
            LOG.errorf(error, "Failed to insert player session for %s", session.playerId)
            false
        }
    }

    fun findByPlayerId(playerId: UUID): java.util.Optional<PlayerSession> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return java.util.Optional.empty()
                        }
                        return java.util.Optional.of(mapSession(resultSet))
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to fetch player session for %s", playerId)
            java.util.Optional.empty()
        }
    }

    fun deleteSession(playerId: UUID): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to delete player session for %s", playerId)
            false
        }
    }

    @Throws(SQLException::class)
    private fun mapSession(resultSet: ResultSet): PlayerSession {
        val playerId = resultSet.getObject("player_id", UUID::class.java)
        val connectedAt = resultSet.getTimestamp("connected_at").toInstant()
        return PlayerSession(playerId, connectedAt)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionRepository::class.java)

        private const val INSERT_SESSION = """
            INSERT INTO player_sessions (player_id, connected_at)
            VALUES (?, ?)
            ON CONFLICT (player_id) DO NOTHING
            """
        private const val SELECT_BY_PLAYER = """
            SELECT player_id, connected_at
            FROM player_sessions
            WHERE player_id = ?
            """
        private const val DELETE_BY_PLAYER = """
            DELETE FROM player_sessions
            WHERE player_id = ?
            """
    }
}
