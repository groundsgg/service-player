package gg.grounds.persistence

import gg.grounds.data.Cached
import gg.grounds.data.Fresh
import gg.grounds.domain.PlayerSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerSessionRepository @Inject constructor(private val dataSource: DataSource) {
    enum class DeleteSessionResult {
        REMOVED,
        NOT_FOUND,
        ERROR,
    }

    sealed interface TouchSessionsResult {
        data class Updated(val count: Int) : TouchSessionsResult

        data object Error : TouchSessionsResult
    }

    data class ServerPlayerCount(val serverName: String, val players: Int)

    data class ProxyPlayerCount(val proxyId: String, val region: String?, val players: Int)

    sealed interface CountPlayersByServerResult {
        data class Counted(val servers: List<ServerPlayerCount>, val total: Int) :
            CountPlayersByServerResult

        data object Error : CountPlayersByServerResult
    }

    sealed interface CountPlayersByProxyResult {
        data class Counted(val proxies: List<ProxyPlayerCount>, val total: Int) :
            CountPlayersByProxyResult

        data object Error : CountPlayersByProxyResult
    }

    @Fresh
    fun insertSession(session: PlayerSession): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_SESSION).use { statement ->
                    statement.setObject(1, session.playerId)
                    statement.setTimestamp(2, Timestamp.from(session.connectedAt))
                    statement.setTimestamp(3, Timestamp.from(session.lastSeenAt))
                    statement.setString(4, session.playerName)
                    statement.setString(5, session.proxyId)
                    statement.setString(6, session.serverName)
                    statement.setString(7, session.region)
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session insert failed (playerId=%s, reason=sql_error)",
                session.playerId,
            )
            false
        }
    }

    /**
     * Insert-or-overwrite in one statement, for the two cases where the caller has decided the
     * existing row lost: a stale session, or a takeover by another proxy. Atomic on purpose — a
     * separate delete + insert leaves a window in which a concurrent logout removes the row that
     * was just written.
     */
    @Fresh
    fun replaceSession(session: PlayerSession): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(REPLACE_SESSION).use { statement ->
                    statement.setObject(1, session.playerId)
                    statement.setTimestamp(2, Timestamp.from(session.connectedAt))
                    statement.setTimestamp(3, Timestamp.from(session.lastSeenAt))
                    statement.setString(4, session.playerName)
                    statement.setString(5, session.proxyId)
                    statement.setString(6, session.serverName)
                    statement.setString(7, session.region)
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session replace failed (playerId=%s, reason=sql_error)",
                session.playerId,
            )
            false
        }
    }

    @Fresh
    fun findByPlayerId(playerId: UUID): PlayerSession? {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) mapSession(resultSet) else null
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session fetch failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            null
        }
    }

    @Fresh
    fun findByName(name: String): PlayerSession? {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_BY_NAME).use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) mapSession(resultSet) else null
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session lookup by name failed (name=%s, reason=sql_error)",
                name,
            )
            null
        }
    }

    /**
     * The one read here that may be stale. Tab-complete fires once per keystroke per player, so it
     * is the hottest query this service has — and a suggestion list that is ten seconds old shows a
     * name that just went offline, which is cosmetic rather than wrong. Everything else in this
     * class is presence, where a stale answer would route a player to a server they already left.
     *
     * Ten seconds, not longer: the list is derived from who is online, and a player who just joined
     * being unsuggestable for a minute is noticeable. Bounded at 2000 entries because the key is
     * the prefix — cardinality is whatever players type, not whatever exists.
     */
    @Cached(ttlSeconds = 10, maxEntries = 2_000)
    fun suggestNames(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()
        val upperBound = prefixUpperBound(lower) ?: return emptyList()
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SUGGEST_NAMES).use { statement ->
                    statement.setString(1, lower)
                    statement.setString(2, upperBound)
                    statement.setString(3, escapeLikePattern(prefix))
                    statement.setInt(4, limit)
                    statement.executeQuery().use { resultSet ->
                        val names = mutableListOf<String>()
                        while (resultSet.next()) {
                            names.add(resultSet.getString("player_name"))
                        }
                        names
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session name suggestion failed (prefix=%s, reason=sql_error)",
                prefix,
            )
            emptyList()
        }
    }

    @Fresh
    fun updateServer(playerId: UUID, serverName: String): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_SERVER).use { statement ->
                    statement.setString(1, serverName)
                    statement.setObject(2, playerId)
                    statement.executeUpdate() > 0
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session server update failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            false
        }
    }

    @Fresh
    fun deleteSession(playerId: UUID): DeleteSessionResult {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_BY_PLAYER).use { statement ->
                    statement.setObject(1, playerId)
                    if (statement.executeUpdate() > 0) DeleteSessionResult.REMOVED
                    else DeleteSessionResult.NOT_FOUND
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session delete failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            DeleteSessionResult.ERROR
        }
    }

    /**
     * Removes the session only while it still belongs to [proxyId]. The logout of a transferred
     * player arrives from the proxy they *left* — by then the session may already belong to the
     * proxy they arrived on, and that session must survive. NOT_FOUND covers both "no session" and
     * "session owned by someone else", which is exactly what the leaving proxy should treat it as:
     * nothing of yours to clean up.
     */
    @Fresh
    fun deleteSessionOwnedBy(playerId: UUID, proxyId: String): DeleteSessionResult {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_BY_PLAYER_AND_PROXY).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setString(2, proxyId)
                    if (statement.executeUpdate() > 0) DeleteSessionResult.REMOVED
                    else DeleteSessionResult.NOT_FOUND
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session delete failed (playerId=%s, proxyId=%s, reason=sql_error)",
                playerId,
                proxyId,
            )
            DeleteSessionResult.ERROR
        }
    }

    @Fresh
    fun touchSessions(playerIds: Collection<UUID>, lastSeenAt: Instant): TouchSessionsResult {
        if (playerIds.isEmpty()) {
            return TouchSessionsResult.Updated(0)
        }

        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_LAST_SEEN_BATCH).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(lastSeenAt))
                    val array = connection.createArrayOf("uuid", playerIds.toTypedArray())
                    statement.setArray(2, array)
                    TouchSessionsResult.Updated(statement.executeUpdate())
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session batch update failed (count=%d, reason=sql_error)",
                playerIds.size,
            )
            TouchSessionsResult.Error
        }
    }

    @Fresh
    fun deleteStaleSessions(cutoff: Instant): Int {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_STALE).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(cutoff))
                    statement.executeUpdate()
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Stale player session cleanup failed (cutoff=%s, reason=sql_error)",
                cutoff,
            )
            0
        }
    }

    /**
     * Per-server player counts plus the network total, in one snapshot. Velocity can only see the
     * players connected to itself, so a network-wide count has to come from here instead.
     */
    @Fresh
    fun countPlayersByServer(): CountPlayersByServerResult {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(COUNT_PLAYERS_BY_SERVER).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        val servers = mutableListOf<ServerPlayerCount>()
                        var total = 0
                        while (resultSet.next()) {
                            if (resultSet.getInt("is_total") == 1) {
                                total = resultSet.getInt("players")
                            } else {
                                val serverName = resultSet.getString("server_name")
                                if (!serverName.isNullOrBlank()) {
                                    servers.add(
                                        ServerPlayerCount(serverName, resultSet.getInt("players"))
                                    )
                                }
                            }
                        }
                        CountPlayersByServerResult.Counted(servers, total)
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Player session count by server failed (reason=sql_error)")
            CountPlayersByServerResult.Error
        }
    }

    /**
     * Per-proxy player counts plus the network total, with each proxy's region.
     *
     * Unlike the per-server count, every session belongs to exactly one proxy — so the total equals
     * the sum of the rows rather than exceeding it. A proxy holding nobody is absent rather than
     * zero: the caller cannot enumerate proxies, and an idle one is not interesting.
     */
    @Fresh
    fun countPlayersByProxy(): CountPlayersByProxyResult {
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(COUNT_PLAYERS_BY_PROXY).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        val proxies = mutableListOf<ProxyPlayerCount>()
                        var total = 0
                        while (resultSet.next()) {
                            if (resultSet.getInt("is_total") != 0) {
                                total = resultSet.getInt("players")
                            } else {
                                val proxyId = resultSet.getString("proxy_id")
                                if (!proxyId.isNullOrBlank()) {
                                    proxies.add(
                                        ProxyPlayerCount(
                                            proxyId,
                                            resultSet.getString("region"),
                                            resultSet.getInt("players"),
                                        )
                                    )
                                }
                            }
                        }
                        CountPlayersByProxyResult.Counted(proxies, total)
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Player session count by proxy failed (reason=sql_error)")
            CountPlayersByProxyResult.Error
        }
    }

    private fun mapSession(resultSet: ResultSet): PlayerSession {
        val playerId =
            requireNotNull(resultSet.getObject("player_id", UUID::class.java)) {
                "player_id is null"
            }
        val connectedAt =
            requireNotNull(resultSet.getTimestamp("connected_at")) { "connected_at is null" }
                .toInstant()
        val lastSeenAt =
            requireNotNull(resultSet.getTimestamp("last_seen_at")) { "last_seen_at is null" }
                .toInstant()
        return PlayerSession(
            playerId,
            connectedAt,
            lastSeenAt,
            resultSet.getString("player_name"),
            resultSet.getString("proxy_id"),
            resultSet.getString("server_name"),
            resultSet.getString("region"),
        )
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionRepository::class.java)

        private const val INSERT_SESSION =
            """
            INSERT INTO player_sessions (player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_id) DO NOTHING
            """
        private const val SELECT_BY_PLAYER =
            """
            SELECT player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region
            FROM player_sessions
            WHERE player_id = ?
            """
        private const val SELECT_BY_NAME =
            """
            SELECT player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region
            FROM player_sessions
            WHERE lower(player_name) = lower(?)
            """
        /**
         * The `~>=~` / `~<~` range is what carries the index, not the LIKE.
         *
         * `lower(player_name) LIKE $1 || '%'` alone plans as a **sequential scan** whenever
         * Postgres picks a generic plan — which pgjdbc invites by switching to server-side prepared
         * statements after five executions. Tab-complete is the hottest path there is (one call per
         * keystroke, per player), so a scan of every session is exactly the thing to avoid.
         * `starts_with()` plans the same way. Verified with `EXPLAIN` under `plan_cache_mode =
         * force_generic_plan`.
         *
         * The pattern operators are the ones the `text_pattern_ops` index actually implements, so
         * the range is index-backed under any plan; the LIKE stays as an exact re-check on the few
         * rows the range returns (the range is byte-wise, so it can be marginally wider than the
         * prefix).
         */
        private const val SUGGEST_NAMES =
            """
            SELECT player_name
            FROM player_sessions
            WHERE lower(player_name) ~>=~ ?
              AND lower(player_name) ~<~ ?
              AND lower(player_name) LIKE lower(?) || '%'
            ORDER BY player_name
            LIMIT ?
            """
        private const val UPDATE_SERVER =
            """
            UPDATE player_sessions
            SET server_name = ?
            WHERE player_id = ?
            """
        private const val REPLACE_SESSION =
            """
            INSERT INTO player_sessions (player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_id) DO UPDATE
            SET connected_at = EXCLUDED.connected_at,
                last_seen_at = EXCLUDED.last_seen_at,
                player_name = EXCLUDED.player_name,
                proxy_id = EXCLUDED.proxy_id,
                server_name = EXCLUDED.server_name,
                region = EXCLUDED.region
            """
        private const val DELETE_BY_PLAYER =
            """
            DELETE FROM player_sessions
            WHERE player_id = ?
            """
        private const val DELETE_BY_PLAYER_AND_PROXY =
            """
            DELETE FROM player_sessions
            WHERE player_id = ? AND proxy_id = ?
            """
        private const val UPDATE_LAST_SEEN_BATCH =
            """
            UPDATE player_sessions
            SET last_seen_at = ?
            WHERE player_id = ANY(?)
            """
        private const val DELETE_STALE =
            """
            DELETE FROM player_sessions
            WHERE last_seen_at < ?
            """

        /**
         * One statement, one snapshot — no race between a per-server query and a separate total
         * query while sessions are being inserted/deleted concurrently.
         *
         * `GROUPING SETS ((server_name), ())` produces one row per distinct `server_name`
         * (including a row for `NULL`, i.e. players who have not reached a backend yet) plus one
         * extra row for the empty grouping set `()`, which aggregates over *all* rows regardless of
         * server_name — that row is the network total. `GROUPING(server_name)` is 1 only on that
         * rolled-up row, so it is how the total row is told apart from the real "no server yet"
         * group, which would otherwise also show up with `server_name IS NULL`.
         */
        /**
         * The same GROUPING SETS shape as COUNT_PLAYERS_BY_SERVER, grouped on the pair `(proxy_id,
         * region)` — a proxy has exactly one region, so grouping by both adds the region to each
         * row without splitting any group.
         *
         * `GROUPING(proxy_id, region)` is a bitmask over both columns, so the rolled-up total row
         * is 3 rather than 1. Comparing against 0 for the real groups is the robust test either
         * way.
         */
        private const val COUNT_PLAYERS_BY_PROXY =
            """
            SELECT proxy_id, region, COUNT(*) AS players, GROUPING(proxy_id, region) AS is_total
            FROM player_sessions
            GROUP BY GROUPING SETS ((proxy_id, region), ())
            """

        private const val COUNT_PLAYERS_BY_SERVER =
            """
            SELECT server_name, COUNT(*) AS players, GROUPING(server_name) AS is_total
            FROM player_sessions
            GROUP BY GROUPING SETS ((server_name), ())
            """

        /**
         * Escapes LIKE metacharacters (`\`, `%`, `_`) in [prefix] so it is safe to use as a literal
         * prefix in `LIKE lower(?) || '%'`. Without this, a player typing `%` or `_` into a
         * name-suggestion search would trigger a full table scan instead of a prefix match.
         */
        internal fun escapeLikePattern(prefix: String): String {
            return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }

        /**
         * The exclusive upper end of the range covering everything that starts with [prefix] —
         * "dah" → "dai" — so the index can answer a prefix search as a range scan.
         *
         * Null when there is no such bound (an empty prefix, or one ending in the highest possible
         * character): the caller then has nothing to narrow the scan with and should not run the
         * query at all.
         */
        internal fun prefixUpperBound(prefix: String): String? {
            val chars = prefix.toCharArray()
            for (i in chars.indices.reversed()) {
                if (chars[i] < Char.MAX_VALUE) {
                    chars[i] = chars[i] + 1
                    return String(chars, 0, i + 1)
                }
            }
            return null
        }
    }
}
