package gg.grounds.persistence.permissions

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.domain.permissions.GroupPermissionGrant
import gg.grounds.domain.permissions.PermissionGroup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class GroupRepository @Inject constructor(private val dataSource: DataSource) {
    fun createGroup(groupName: String): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_GROUP).use { statement ->
                    statement.setString(1, groupName)
                    if (statement.executeUpdate() > 0) ApplyOutcome.CREATED
                    else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to create permission group (groupName=%s)", groupName)
            ApplyOutcome.ERROR
        }
    }

    fun deleteGroup(groupName: String): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_GROUP).use { statement ->
                    statement.setString(1, groupName)
                    if (statement.executeUpdate() > 0) ApplyOutcome.DELETED
                    else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to delete permission group (groupName=%s)", groupName)
            ApplyOutcome.ERROR
        }
    }

    fun getGroup(groupName: String, includePermissions: Boolean): PermissionGroup? {
        return try {
            dataSource.connection.use { connection ->
                if (!includePermissions) {
                    connection.prepareStatement(SELECT_GROUP).use { statement ->
                        statement.setString(1, groupName)
                        statement.executeQuery().use { resultSet ->
                            return if (resultSet.next()) PermissionGroup(groupName, emptySet())
                            else null
                        }
                    }
                }

                connection.prepareStatement(SELECT_GROUP_WITH_PERMISSIONS).use { statement ->
                    statement.setString(1, groupName)
                    statement.executeQuery().use { resultSet ->
                        buildGroupFromResult(groupName, resultSet)
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to load permission group (groupName=%s, includePermissions=%s)",
                groupName,
                includePermissions,
            )
            throw PermissionsRepositoryException("Failed to load permission group", error)
        }
    }

    fun listGroups(includePermissions: Boolean): List<PermissionGroup> {
        return try {
            dataSource.connection.use { connection ->
                if (!includePermissions) {
                    connection.prepareStatement(SELECT_GROUPS).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            val groups = mutableListOf<PermissionGroup>()
                            while (resultSet.next()) {
                                groups.add(PermissionGroup(resultSet.getString("name"), emptySet()))
                            }
                            return groups
                        }
                    }
                }

                connection.prepareStatement(SELECT_GROUPS_WITH_PERMISSIONS).use { statement ->
                    statement.executeQuery().use { resultSet -> buildGroupsFromResult(resultSet) }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to list permission groups (includePermissions=%s)",
                includePermissions,
            )
            throw PermissionsRepositoryException("Failed to list permission groups", error)
        }
    }

    fun addGroupPermissions(
        groupName: String,
        permissionGrants: Collection<GroupPermissionGrant>,
    ): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_GROUP_PERMISSION).use { statement ->
                    permissionGrants.forEach { grant ->
                        statement.setString(1, groupName)
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
                "Failed to add permissions to group (groupName=%s, permissionGrantsCount=%d)",
                groupName,
                permissionGrants.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun removeGroupPermissions(groupName: String, permissions: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_GROUP_PERMISSION).use { statement ->
                    permissions.forEach { permission ->
                        statement.setString(1, groupName)
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
                "Failed to remove permissions from group (groupName=%s, permissionsCount=%d)",
                groupName,
                permissions.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun listGroupsWithExpiredPermissions(since: Instant, until: Instant): Set<String> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_EXPIRED_GROUP_PERMISSIONS).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(since))
                    statement.setTimestamp(2, Timestamp.from(until))
                    statement.executeQuery().use { resultSet ->
                        val groups = linkedSetOf<String>()
                        while (resultSet.next()) {
                            resultSet.getString("group_name")?.let { groups.add(it) }
                        }
                        groups
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to list expired group permissions (since=%s, until=%s)",
                since,
                until,
            )
            emptySet()
        }
    }

    private fun buildGroupFromResult(groupName: String, resultSet: ResultSet): PermissionGroup? {
        val permissions = linkedSetOf<GroupPermissionGrant>()
        var sawGroup = false
        while (resultSet.next()) {
            sawGroup = true
            resultSet.getString("permission")?.let { permission ->
                permissions.add(
                    GroupPermissionGrant(
                        permission,
                        resultSet.getTimestamp("expires_at")?.toInstant(),
                    )
                )
            }
        }
        return if (sawGroup) PermissionGroup(groupName, permissions) else null
    }

    private fun buildGroupsFromResult(resultSet: ResultSet): List<PermissionGroup> {
        val groups = mutableListOf<PermissionGroup>()
        var currentName: String? = null
        var currentPermissions = linkedSetOf<GroupPermissionGrant>()
        while (resultSet.next()) {
            val name = resultSet.getString("name")
            if (currentName == null) {
                currentName = name
            }
            if (name != currentName) {
                groups.add(PermissionGroup(requireNotNull(currentName), currentPermissions))
                currentName = name
                currentPermissions = linkedSetOf()
            }
            resultSet.getString("permission")?.let { permission ->
                currentPermissions.add(
                    GroupPermissionGrant(
                        permission,
                        resultSet.getTimestamp("expires_at")?.toInstant(),
                    )
                )
            }
        }
        currentName?.let { groups.add(PermissionGroup(it, currentPermissions)) }
        return groups
    }

    companion object {
        private val LOG = Logger.getLogger(GroupRepository::class.java)

        private const val INSERT_GROUP =
            """
            INSERT INTO permission_groups (name)
            VALUES (?)
            ON CONFLICT (name) DO NOTHING
            """
        private const val DELETE_GROUP =
            """
            DELETE FROM permission_groups
            WHERE name = ?
            """
        private const val SELECT_GROUP =
            """
            SELECT name
            FROM permission_groups
            WHERE name = ?
            """
        private const val SELECT_GROUPS =
            """
            SELECT name
            FROM permission_groups
            ORDER BY name
            """
        private const val SELECT_GROUP_WITH_PERMISSIONS =
            """
            SELECT pg.name, gp.permission, gp.expires_at
            FROM permission_groups pg
            LEFT JOIN group_permissions gp ON gp.group_name = pg.name
            WHERE pg.name = ?
            ORDER BY gp.permission
            """
        private const val SELECT_GROUPS_WITH_PERMISSIONS =
            """
            SELECT pg.name, gp.permission, gp.expires_at
            FROM permission_groups pg
            LEFT JOIN group_permissions gp ON gp.group_name = pg.name
            ORDER BY pg.name, gp.permission
            """
        private const val INSERT_GROUP_PERMISSION =
            """
            INSERT INTO group_permissions (group_name, permission, expires_at)
            VALUES (?, ?, ?)
            ON CONFLICT (group_name, permission)
            DO UPDATE SET expires_at = EXCLUDED.expires_at
            """
        private const val DELETE_GROUP_PERMISSION =
            """
            DELETE FROM group_permissions
            WHERE group_name = ?
              AND permission = ?
            """
        private const val SELECT_EXPIRED_GROUP_PERMISSIONS =
            """
            SELECT DISTINCT group_name
            FROM group_permissions
            WHERE expires_at IS NOT NULL
              AND expires_at > ?
              AND expires_at <= ?
            """
    }
}
