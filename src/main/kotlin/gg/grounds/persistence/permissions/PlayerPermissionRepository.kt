package gg.grounds.persistence.permissions

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.domain.permissions.PlayerPermissionGrant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerPermissionRepository @Inject constructor(private val dataSource: DataSource) {
    fun addPlayerPermissions(
        playerId: UUID,
        permissionGrants: Collection<PlayerPermissionGrant>,
    ): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_PLAYER_PERMISSION).use { statement ->
                    permissionGrants.forEach { grant ->
                        statement.setObject(1, playerId)
                        statement.setString(2, grant.permission)
                        statement.setTimestamp(3, grant.expiresAt?.let { Timestamp.from(it) })
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
                permissionGrants.size,
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

    companion object {
        private val LOG = Logger.getLogger(PlayerPermissionRepository::class.java)

        private const val INSERT_PLAYER_PERMISSION =
            """
            INSERT INTO player_permissions (player_id, permission, expires_at)
            VALUES (?, ?, ?)
            ON CONFLICT (player_id, permission)
            DO UPDATE SET expires_at = EXCLUDED.expires_at
            """
        private const val DELETE_PLAYER_PERMISSION =
            """
            DELETE FROM player_permissions
            WHERE player_id = ?
              AND permission = ?
            """
    }
}
