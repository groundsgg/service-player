package gg.grounds.persistence

import gg.grounds.data.Fresh
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger
import org.postgresql.util.PGobject

/**
 * A player's customised kits.
 *
 * The stored value is the game's own item encoding and is never interpreted here — see the
 * migration for why that is safe. What this service owns is the identity of the row and its
 * durability.
 *
 * Reads and writes are [Fresh] rather than cached. A loadout is read once, on the way into a match,
 * and written once, when a player presses save; caching either would only add a window in which a
 * player's own edit is invisible to them, which is the single most obvious way for this to feel
 * broken.
 */
@ApplicationScoped
class PlayerLoadoutRepository @Inject constructor(private val dataSource: DataSource) {

    /** What a read found. */
    sealed interface Result {
        data class Found(val slots: String) : Result

        /** No such loadout — the player has never customised this kit. */
        data object Absent : Result

        /** The store could not answer. Distinct from [Absent] on purpose. */
        data object Unavailable : Result
    }

    @Fresh
    fun find(playerId: UUID, kitId: String): Result =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, kitId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) Result.Found(resultSet.getString("slots"))
                        else Result.Absent
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Loadout fetch failed (playerId=%s, kitId=%s, reason=sql_error)",
                playerId,
                kitId,
            )
            Result.Unavailable
        }

    /**
     * Store an arrangement, replacing whatever was there.
     *
     * An upsert rather than an insert-or-update pair: a player pressing save twice must not be an
     * error, and the second save is the one that counts.
     *
     * @return false when the store could not be written.
     */
    @Fresh
    fun save(playerId: UUID, kitId: String, slots: String): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPSERT).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, kitId)
                    statement.setObject(3, jsonb(slots))
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Loadout save failed (playerId=%s, kitId=%s, reason=sql_error)",
                playerId,
                kitId,
            )
            false
        }

    /**
     * Forget an arrangement, so the player goes back to the stock kit.
     *
     * Deleting one that is not there is a success: the caller asked for "no customisation" and that
     * is the state they end up in either way.
     */
    @Fresh
    fun delete(playerId: UUID, kitId: String): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, kitId)
                    statement.executeUpdate()
                    true
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Loadout delete failed (playerId=%s, kitId=%s, reason=sql_error)",
                playerId,
                kitId,
            )
            false
        }

    private fun jsonb(value: String): PGobject =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }

    private companion object {
        private val LOG: Logger = Logger.getLogger(PlayerLoadoutRepository::class.java)

        const val SELECT = "SELECT slots FROM player_loadout WHERE player_id = ? AND kit_id = ?"

        const val UPSERT =
            """
            INSERT INTO player_loadout (player_id, kit_id, slots, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (player_id, kit_id)
            DO UPDATE SET slots = EXCLUDED.slots, updated_at = now()
            """

        const val DELETE = "DELETE FROM player_loadout WHERE player_id = ? AND kit_id = ?"
    }
}
