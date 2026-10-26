package gg.grounds.persistence

import gg.grounds.data.Cached
import gg.grounds.data.Fresh
import gg.grounds.persistence.PlayerSessionRepository.Companion.escapeLikePattern
import gg.grounds.persistence.PlayerSessionRepository.Companion.prefixUpperBound
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

data class KnownPlayerName(val playerId: UUID, val playerName: String)

/** Identity reads from the durable name index; these do not assert online presence. */
@ApplicationScoped
class KnownPlayerDirectoryRepository @Inject constructor(private val dataSource: DataSource) {
    @Cached(ttlSeconds = 10, maxEntries = 2_000)
    fun search(prefix: String, limit: Int): List<KnownPlayerName> {
        if (prefix.length !in 2..64 || limit <= 0) return emptyList()
        val lower = prefix.lowercase()
        val upper = prefixUpperBound(lower) ?: return emptyList()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(SEARCH).use { statement ->
                statement.setString(1, lower)
                statement.setString(2, upper)
                statement.setString(3, escapeLikePattern(prefix))
                statement.setInt(4, limit.coerceAtMost(25))
                statement.executeQuery().use(::readNames)
            }
        }
    }

    /** Two identities mean ambiguity; choosing either one would target the wrong player. */
    @Fresh
    fun resolve(name: String): List<KnownPlayerName> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(RESOLVE).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use(::readNames)
            }
        }

    private fun readNames(rows: ResultSet): List<KnownPlayerName> = buildList {
        while (rows.next()) {
            add(
                KnownPlayerName(
                    rows.getObject("player_id", UUID::class.java),
                    rows.getString("player_name"),
                )
            )
        }
    }

    private companion object {
        const val SEARCH =
            """
            SELECT player_id, player_name FROM player_names
            WHERE lower(player_name) ~>=~ ? AND lower(player_name) ~<~ ?
              AND lower(player_name) LIKE lower(?) || '%'
            ORDER BY player_name, player_id LIMIT ?
        """
        const val RESOLVE =
            """
            SELECT player_id, player_name FROM player_names
            WHERE lower(player_name)=lower(?)
            ORDER BY player_id LIMIT 2
        """
    }
}
