package gg.grounds.persistence.permissions

import gg.grounds.domain.PlayerPermissionsData
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PermissionsQueryRepository @Inject constructor(private val dataSource: DataSource) {
    fun getPlayerPermissions(
        playerId: UUID,
        includeEffectivePermissions: Boolean,
        includeDirectPermissions: Boolean,
        includeGroups: Boolean,
    ): PlayerPermissionsData? {
        return try {
            dataSource.connection.use { connection ->
                val shouldLoadGroups = includeGroups || includeEffectivePermissions
                val shouldLoadDirect = includeDirectPermissions || includeEffectivePermissions

                val loadedGroupNames =
                    if (shouldLoadGroups) {
                        connection.prepareStatement(SELECT_PLAYER_GROUPS).use { statement ->
                            statement.setObject(1, playerId)
                            statement.executeQuery().use { resultSet ->
                                val names = linkedSetOf<String>()
                                while (resultSet.next()) {
                                    resultSet.getString("group_name")?.let { names.add(it) }
                                }
                                names
                            }
                        }
                    } else {
                        emptySet()
                    }

                val loadedDirectPermissions =
                    if (shouldLoadDirect) {
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
                    } else {
                        emptySet()
                    }

                val groupNames = if (includeGroups) loadedGroupNames else emptySet()
                val directPermissions =
                    if (includeDirectPermissions) loadedDirectPermissions else emptySet()

                val effectivePermissions =
                    if (includeEffectivePermissions) {
                        val permissions = linkedSetOf<String>()
                        permissions.addAll(loadedDirectPermissions)
                        connection.prepareStatement(SELECT_GROUP_PERMISSIONS_FOR_PLAYER).use {
                            statement ->
                            statement.setObject(1, playerId)
                            statement.executeQuery().use { resultSet ->
                                while (resultSet.next()) {
                                    resultSet.getString("permission")?.let { permissions.add(it) }
                                }
                            }
                        }
                        permissions
                    } else {
                        emptySet()
                    }

                PlayerPermissionsData(playerId, groupNames, directPermissions, effectivePermissions)
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to load player permissions (playerId=%s, includeEffectivePermissions=%s, includeDirectPermissions=%s, includeGroups=%s)",
                playerId,
                includeEffectivePermissions,
                includeDirectPermissions,
                includeGroups,
            )
            null
        }
    }

    fun checkPlayerPermission(playerId: UUID, permission: String): Boolean? {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(CHECK_PLAYER_PERMISSION).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, permission)
                    statement.setObject(3, playerId)
                    statement.setString(4, permission)
                    statement.executeQuery().use { resultSet -> resultSet.next() }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to check player permission (playerId=%s, permission=%s)",
                playerId,
                permission,
            )
            null
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PermissionsQueryRepository::class.java)

        private const val SELECT_PLAYER_GROUPS =
            """
            SELECT group_name
            FROM player_groups
            WHERE player_id = ?
            ORDER BY group_name
            """
        private const val SELECT_PLAYER_PERMISSIONS =
            """
            SELECT permission
            FROM player_permissions
            WHERE player_id = ?
            ORDER BY permission
            """
        private const val SELECT_GROUP_PERMISSIONS_FOR_PLAYER =
            """
            SELECT gp.permission
            FROM group_permissions gp
            JOIN player_groups pg ON gp.group_name = pg.group_name
            WHERE pg.player_id = ?
            ORDER BY gp.permission
            """
        private const val CHECK_PLAYER_PERMISSION =
            """
            SELECT 1
            FROM player_permissions
            WHERE player_id = ?
              AND permission = ?
            UNION
            SELECT 1
            FROM group_permissions gp
            JOIN player_groups pg ON gp.group_name = pg.group_name
            WHERE pg.player_id = ?
              AND gp.permission = ?
            LIMIT 1
            """
    }
}
