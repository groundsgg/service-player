package gg.grounds.persistence

import gg.grounds.data.Fresh
import gg.grounds.profile.PlayerProfile
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import javax.sql.DataSource

/** Authoritative reads and atomic writes; no proxy-local or regional settings cache. */
@ApplicationScoped
class PlayerProfileRepository(private val dataSource: DataSource) {
    @Fresh
    fun read(player: UUID): PlayerProfile =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT bio, pronouns, favourite_mode, show_favourite_mode, favourite_modes FROM player_profiles WHERE player_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, player)
                    statement.executeQuery().use { rows ->
                        if (rows.next())
                            PlayerProfile(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getBoolean(4),
                                rows.getArray(5).let { array ->
                                    try {
                                        (array.array as Array<*>).map { it.toString() }
                                    } finally {
                                        array.free()
                                    }
                                },
                            )
                        else PlayerProfile()
                    }
                }
        }

    @Fresh
    fun save(
        player: UUID,
        profile: PlayerProfile,
        replaceFavourites: Boolean = true,
    ): PlayerProfile {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO player_profiles (player_id, bio, pronouns, favourite_mode, show_favourite_mode, favourite_modes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (player_id) DO UPDATE SET bio = EXCLUDED.bio, pronouns = EXCLUDED.pronouns,
                        favourite_mode = EXCLUDED.favourite_mode, show_favourite_mode = EXCLUDED.show_favourite_mode,
                        favourite_modes = CASE WHEN ? OR player_profiles.favourite_mode IS DISTINCT FROM EXCLUDED.favourite_mode
                            THEN EXCLUDED.favourite_modes ELSE player_profiles.favourite_modes END
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, player)
                    statement.setString(2, profile.bio)
                    statement.setString(3, profile.pronouns)
                    statement.setString(4, profile.favouriteMode)
                    statement.setBoolean(5, profile.showFavouriteMode)
                    val modes =
                        connection.createArrayOf("varchar", profile.favouriteModes.toTypedArray())
                    try {
                        statement.setArray(6, modes)
                        statement.setBoolean(7, replaceFavourites)
                        statement.executeUpdate()
                    } finally {
                        modes.free()
                    }
                }
        }
        return read(player)
    }
}
