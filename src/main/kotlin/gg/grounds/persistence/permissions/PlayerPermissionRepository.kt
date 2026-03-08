package gg.grounds.persistence.permissions

import gg.grounds.domain.ApplyOutcome
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerPermissionRepository @Inject constructor(private val dataSource: DataSource) {
    fun addPlayerPermissions(playerId: UUID, permissions: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_PLAYER_PERMISSION).use { statement ->
                    permissions.forEach { permission ->
                        statement.setObject(1, playerId)
                        statement.setString(2, permission)
                        statement.addBatch()
                    }
                    val updated = BatchUpdateHelper.countSuccessful(statement.executeBatch()) > 0
                    if (updated) ApplyOutcome.UPDATED else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to add player permissions (playerId=%s, permissionsCount=%d)",
                playerId,
                permissions.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun removePlayerPermissions(playerId: UUID, permissions: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_PLAYER_PERMISSION).use { statement ->
                    permissions.forEach { permission ->
                        statement.setObject(1, playerId)
                        statement.setString(2, permission)
                        statement.addBatch()
                    }
                    val updated = BatchUpdateHelper.countSuccessful(statement.executeBatch()) > 0
                    if (updated) ApplyOutcome.UPDATED else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to remove player permissions (playerId=%s, permissionsCount=%d)",
                playerId,
                permissions.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun getPlayerPermissions(playerId: UUID): Set<String> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_PLAYER_PERMISSIONS).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        val permissions = linkedSetOf<String>()
                        while (resultSet.next()) {
                            resultSet.getString("permission")?.let { permissions.add(it) }
                        }
                        permissions
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to load player permissions (playerId=%s)", playerId)
            emptySet()
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPermissionRepository::class.java)

        private const val INSERT_PLAYER_PERMISSION =
            """
            INSERT INTO player_permissions (player_id, permission)
            VALUES (?, ?)
            ON CONFLICT (player_id, permission) DO NOTHING
            """
        private const val DELETE_PLAYER_PERMISSION =
            """
            DELETE FROM player_permissions
            WHERE player_id = ?
              AND permission = ?
            """
        private const val SELECT_PLAYER_PERMISSIONS =
            """
            SELECT permission
            FROM player_permissions
            WHERE player_id = ?
            ORDER BY permission
            """
    }
}
