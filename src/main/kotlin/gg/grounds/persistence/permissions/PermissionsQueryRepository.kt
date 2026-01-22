package gg.grounds.persistence.permissions

import gg.grounds.domain.permissions.PlayerGroupMembership
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.domain.permissions.PlayerPermissionsData
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
                                val names = linkedSetOf<PlayerGroupMembership>()
                                while (resultSet.next()) {
                                    resultSet.getString("group_name")?.let { groupName ->
                                        names.add(
                                            PlayerGroupMembership(
                                                groupName,
                                                resultSet.getTimestamp("expires_at")?.toInstant(),
                                            )
                                        )
                                    }
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
                                val permissions = linkedSetOf<PlayerPermissionGrant>()
                                while (resultSet.next()) {
                                    resultSet.getString("permission")?.let { permission ->
                                        permissions.add(
                                            PlayerPermissionGrant(
                                                permission,
                                                resultSet.getTimestamp("expires_at")?.toInstant(),
                                            )
                                        )
                                    }
                                }
                                permissions
                            }
                        }
                    } else {
                        emptySet()
                    }

                val groupMemberships = if (includeGroups) loadedGroupNames else emptySet()
                val directPermissionGrants =
                    if (includeDirectPermissions) loadedDirectPermissions else emptySet()

                val effectivePermissions =
                    if (includeEffectivePermissions) {
                        val permissions = linkedSetOf<String>()
                        permissions.addAll(
                            loadedDirectPermissions.map { grant -> grant.permission }
                        )
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

                PlayerPermissionsData(
                    playerId,
                    groupMemberships,
                    directPermissionGrants,
                    effectivePermissions,
                )
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
            SELECT group_name, expires_at
            FROM player_groups
            WHERE player_id = ?
              AND (expires_at IS NULL OR expires_at > now())
            ORDER BY group_name
            """
        private const val SELECT_PLAYER_PERMISSIONS =
            """
            SELECT permission, expires_at
            FROM player_permissions
            WHERE player_id = ?
              AND (expires_at IS NULL OR expires_at > now())
            ORDER BY permission
            """
        private const val SELECT_GROUP_PERMISSIONS_FOR_PLAYER =
            """
            SELECT gp.permission
            FROM group_permissions gp
            JOIN player_groups pg ON gp.group_name = pg.group_name
            WHERE pg.player_id = ?
              AND (pg.expires_at IS NULL OR pg.expires_at > now())
              AND (gp.expires_at IS NULL OR gp.expires_at > now())
            ORDER BY gp.permission
            """
        private const val CHECK_PLAYER_PERMISSION =
            """
            SELECT 1
            FROM player_permissions
            WHERE player_id = ?
              AND permission = ?
              AND (expires_at IS NULL OR expires_at > now())
            UNION
            SELECT 1
            FROM group_permissions gp
            JOIN player_groups pg ON gp.group_name = pg.group_name
            WHERE pg.player_id = ?
              AND gp.permission = ?
              AND (pg.expires_at IS NULL OR pg.expires_at > now())
              AND (gp.expires_at IS NULL OR gp.expires_at > now())
            LIMIT 1
            """
    }
}
