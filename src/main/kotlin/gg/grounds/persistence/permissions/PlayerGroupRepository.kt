package gg.grounds.persistence.permissions

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.domain.permissions.PlayerGroupMembership
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerGroupRepository @Inject constructor(private val dataSource: DataSource) {
    fun addPlayerGroups(
        playerId: UUID,
        groupMemberships: Collection<PlayerGroupMembership>,
    ): ApplyOutcome {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_PLAYER_GROUP).use { statement ->
                    groupMemberships.forEach { membership ->
                        statement.setObject(1, playerId)
                        statement.setString(2, membership.groupName)
                        statement.setTimestamp(3, membership.expiresAt?.let { Timestamp.from(it) })
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
                groupMemberships.size,
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

    fun listActivePlayersForGroup(groupName: String): Set<UUID> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_ACTIVE_PLAYERS_FOR_GROUP).use { statement ->
                    statement.setString(1, groupName)
                    statement.executeQuery().use { resultSet ->
                        val players = linkedSetOf<UUID>()
                        while (resultSet.next()) {
                            val playerId = resultSet.getObject("player_id", UUID::class.java)
                            players.add(playerId)
                        }
                        players
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Failed to list active players for group (groupName=%s)", groupName)
            emptySet()
        }
    }

    fun listPlayersWithExpiredGroups(since: Instant, until: Instant): Set<UUID> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_EXPIRED_PLAYER_GROUPS).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(since))
                    statement.setTimestamp(2, Timestamp.from(until))
                    statement.executeQuery().use { resultSet ->
                        val players = linkedSetOf<UUID>()
                        while (resultSet.next()) {
                            val playerId = resultSet.getObject("player_id", UUID::class.java)
                            players.add(playerId)
                        }
                        players
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Failed to list expired player groups (since=%s, until=%s)",
                since,
                until,
            )
            emptySet()
        }
    }

    fun listPlayersWithGroups(): Set<UUID> {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_PLAYERS_WITH_GROUPS).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        val players = linkedSetOf<UUID>()
                        while (resultSet.next()) {
                            val playerId = resultSet.getObject("player_id", UUID::class.java)
                            players.add(playerId)
                        }
                        players
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "List players with groups failed (reason=sql_exception)")
            emptySet()
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerGroupRepository::class.java)

        private const val INSERT_PLAYER_GROUP =
            """
            INSERT INTO player_groups (player_id, group_name, expires_at)
            VALUES (?, ?, ?)
            ON CONFLICT (player_id, group_name)
            DO UPDATE SET expires_at = EXCLUDED.expires_at
            """
        private const val DELETE_PLAYER_GROUP =
            """
            DELETE FROM player_groups
            WHERE player_id = ?
              AND group_name = ?
            """
        private const val SELECT_ACTIVE_PLAYERS_FOR_GROUP =
            """
            SELECT player_id
            FROM player_groups
            WHERE group_name = ?
              AND (expires_at IS NULL OR expires_at > now())
            """
        private const val SELECT_EXPIRED_PLAYER_GROUPS =
            """
            SELECT DISTINCT player_id
            FROM player_groups
            WHERE expires_at IS NOT NULL
              AND expires_at > ?
              AND expires_at <= ?
            """
        private const val SELECT_PLAYERS_WITH_GROUPS =
            """
            SELECT DISTINCT player_id
            FROM player_groups
            """
    }
}
