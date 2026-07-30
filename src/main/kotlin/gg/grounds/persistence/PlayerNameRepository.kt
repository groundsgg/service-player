package gg.grounds.persistence

import gg.grounds.data.Fresh
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

/**
 * The durable player-name index (`player_names`) — separate from [PlayerSessionRepository] on
 * purpose. That table is presence and its rows are deleted on logout; this one is never deleted, so
 * keeping it in its own repository means the session cleanup paths (`deleteSession`,
 * `deleteStaleSessions`) have no query that could touch it.
 */
@ApplicationScoped
class PlayerNameRepository @Inject constructor(private val dataSource: DataSource) {
    /** Idempotent: a repeat login just refreshes the name + last-seen timestamp. */
    @Fresh
    fun upsertName(playerId: UUID, playerName: String, seenAt: Instant): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPSERT_NAME).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, playerName)
                    statement.setTimestamp(3, Timestamp.from(seenAt))
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player name index upsert failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            false
        }
    }

    /**
     * One query for every id — a `WHERE player_id = ANY(?)`, not N round-trips.
     *
     * Deliberately NOT `@Cached`, even though a durable name index is the textbook case for it. The
     * cache key is the argument, and the argument here is a collection: two callers asking for the
     * same three players in a different order are two entries, and a caller asking for four players
     * shares nothing with one asking for three of them. The hit rate would be poor and the memory
     * spent on it invisible.
     *
     * Caching this properly means caching per id and assembling the batch, which changes the shape
     * of the method rather than adding an annotation to it. Left alone until someone measures that
     * it matters.
     */
    fun findNames(playerIds: Collection<UUID>): Map<UUID, String> {
        if (playerIds.isEmpty()) {
            return emptyMap()
        }
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_NAMES).use { statement ->
                    val array = connection.createArrayOf("uuid", playerIds.toTypedArray())
                    statement.setArray(1, array)
                    statement.executeQuery().use { resultSet ->
                        val names = mutableMapOf<UUID, String>()
                        while (resultSet.next()) {
                            val playerId =
                                requireNotNull(resultSet.getObject("player_id", UUID::class.java)) {
                                    "player_id is null"
                                }
                            names[playerId] = resultSet.getString("player_name")
                        }
                        names
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player name index lookup failed (count=%d, reason=sql_error)",
                playerIds.size,
            )
            emptyMap()
        }
    }

    /**
     * The player's stored language tag, or null when they have set none (the row's `locale` is
     * NULL) or have never logged in (no row). Both mean the same thing to a caller — use the
     * client's announced locale — so they are not distinguished.
     */
    @Fresh
    fun getLocale(playerId: UUID): String? {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_LOCALE).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getString("locale") else null
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player locale fetch failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            null
        }
    }

    /**
     * Sets (or, with a null [locale], clears) the player's language on their durable row. Updates
     * only — the row is written at login, so a player online enough to run `/lang` already has one;
     * a miss (0 rows) returns false rather than inventing a name to insert alongside the locale.
     */
    @Fresh
    fun setLocale(playerId: UUID, locale: String?): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_LOCALE).use { statement ->
                    statement.setString(1, locale)
                    statement.setObject(2, playerId)
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player locale update failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            false
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerNameRepository::class.java)

        private const val UPSERT_NAME =
            """
            INSERT INTO player_names (player_id, player_name, last_seen_at)
            VALUES (?, ?, ?)
            ON CONFLICT (player_id) DO UPDATE
            SET player_name = EXCLUDED.player_name, last_seen_at = EXCLUDED.last_seen_at
            """
        private const val SELECT_NAMES =
            """
            SELECT player_id, player_name
            FROM player_names
            WHERE player_id = ANY(?)
            """
        private const val SELECT_LOCALE = "SELECT locale FROM player_names WHERE player_id = ?"
        private const val UPDATE_LOCALE = "UPDATE player_names SET locale = ? WHERE player_id = ?"
    }
}
