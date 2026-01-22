package gg.grounds.persistence.permissions

import gg.grounds.domain.ApplyOutcome
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerGroupRepository @Inject constructor(private val dataSource: DataSource) {
    fun addPlayerGroups(playerId: UUID, groupNames: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_PLAYER_GROUP).use { statement ->
                    groupNames.forEach { groupName ->
                        statement.setObject(1, playerId)
                        statement.setString(2, groupName)
                        statement.addBatch()
                    }
                    val updated = BatchUpdateHelper.countSuccessful(statement.executeBatch()) > 0
                    if (updated) ApplyOutcome.UPDATED else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to add player groups (playerId=%s, groupCount=%d)",
                playerId,
                groupNames.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun removePlayerGroups(playerId: UUID, groupNames: Collection<String>): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_PLAYER_GROUP).use { statement ->
                    groupNames.forEach { groupName ->
                        statement.setObject(1, playerId)
                        statement.setString(2, groupName)
                        statement.addBatch()
                    }
                    val updated = BatchUpdateHelper.countSuccessful(statement.executeBatch()) > 0
                    if (updated) ApplyOutcome.UPDATED else ApplyOutcome.NO_CHANGE
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to remove player groups (playerId=%s, groupCount=%d)",
                playerId,
                groupNames.size,
            )
            ApplyOutcome.ERROR
        }
    }

    fun getPlayerGroupNames(playerId: UUID): Set<String> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_PLAYER_GROUPS).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        val groupNames = linkedSetOf<String>()
                        while (resultSet.next()) {
                            resultSet.getString("group_name")?.let { groupNames.add(it) }
                        }
                        groupNames
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to load player groups (playerId=%s)", playerId)
            emptySet()
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerGroupRepository::class.java)

        private const val INSERT_PLAYER_GROUP =
            """
            INSERT INTO player_groups (player_id, group_name)
            VALUES (?, ?)
            ON CONFLICT (player_id, group_name) DO NOTHING
            """
        private const val DELETE_PLAYER_GROUP =
            """
            DELETE FROM player_groups
            WHERE player_id = ?
              AND group_name = ?
            """
        private const val SELECT_PLAYER_GROUPS =
            """
            SELECT group_name
            FROM player_groups
            WHERE player_id = ?
            ORDER BY group_name
            """
    }
}
