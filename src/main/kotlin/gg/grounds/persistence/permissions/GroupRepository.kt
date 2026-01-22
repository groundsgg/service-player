package gg.grounds.persistence.permissions

import gg.grounds.domain.ApplyOutcome
import gg.grounds.domain.PermissionGroup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
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
            null
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
            emptyList()
        }
    }

    fun addGroupPermissions(groupName: String, permissions: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_GROUP_PERMISSION).use { statement ->
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
                "Failed to add permissions to group (groupName=%s, permissionsCount=%d)",
                groupName,
                permissions.size,
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

    private fun buildGroupFromResult(groupName: String, resultSet: ResultSet): PermissionGroup? {
        val permissions = linkedSetOf<String>()
        var sawGroup = false
        while (resultSet.next()) {
            sawGroup = true
            resultSet.getString("permission")?.let { permissions.add(it) }
        }
        return if (sawGroup) PermissionGroup(groupName, permissions) else null
    }

    private fun buildGroupsFromResult(resultSet: ResultSet): List<PermissionGroup> {
        val groups = mutableListOf<PermissionGroup>()
        var currentName: String? = null
        var currentPermissions = linkedSetOf<String>()
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
            resultSet.getString("permission")?.let { currentPermissions.add(it) }
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
            SELECT pg.name, gp.permission
            FROM permission_groups pg
            LEFT JOIN group_permissions gp ON gp.group_name = pg.name
            WHERE pg.name = ?
            ORDER BY gp.permission
            """
        private const val SELECT_GROUPS_WITH_PERMISSIONS =
            """
            SELECT pg.name, gp.permission
            FROM permission_groups pg
            LEFT JOIN group_permissions gp ON gp.group_name = pg.name
            ORDER BY pg.name, gp.permission
            """
        private const val INSERT_GROUP_PERMISSION =
            """
            INSERT INTO group_permissions (group_name, permission)
            VALUES (?, ?)
            ON CONFLICT (group_name, permission) DO NOTHING
            """
        private const val DELETE_GROUP_PERMISSION =
            """
            DELETE FROM group_permissions
            WHERE group_name = ?
              AND permission = ?
            """
    }
}
