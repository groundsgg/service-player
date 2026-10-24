package gg.grounds.persistence

import gg.grounds.data.Cached
import gg.grounds.data.Fresh
import gg.grounds.domain.PlayerSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
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

    sealed interface ClaimSessionResult {
        data object Claimed : ClaimSessionResult

        data object AlreadyOnline : ClaimSessionResult

        data object Error : ClaimSessionResult
    }

    sealed interface CreateHandoffResult {
        data object Created : CreateHandoffResult

        data object NoActiveSession : CreateHandoffResult

        data object Error : CreateHandoffResult
    }

    sealed interface ConsumeHandoffResult {
        data object Consumed : ConsumeHandoffResult

        data object NotFound : ConsumeHandoffResult

        data object Error : ConsumeHandoffResult
    }

    data class ServerPlayerCount(val serverName: String, val players: Int)

    data class ProxyPlayerCount(val proxyId: String, val region: String?, val players: Int)

    data class Playtime(val totalMillis: Long, val playMillis: Long)

    @Fresh
    fun claimSession(session: PlayerSession, staleBefore: Instant): ClaimSessionResult {
        val sessionId = session.sessionId ?: return ClaimSessionResult.Error
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(CLAIM_SESSION).use { statement ->
                    statement.setObject(1, session.playerId)
                    statement.setTimestamp(2, Timestamp.from(session.connectedAt))
                    statement.setTimestamp(3, Timestamp.from(session.lastSeenAt))
                    statement.setString(4, session.playerName)
                    statement.setString(5, session.proxyId)
                    statement.setString(6, session.serverName)
                    statement.setString(7, session.region)
                    statement.setObject(8, sessionId)
                    statement.setTimestamp(9, Timestamp.from(staleBefore))
                    if (statement.executeUpdate() > 0) ClaimSessionResult.Claimed
                    else ClaimSessionResult.AlreadyOnline
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session claim failed (playerId=%s, reason=sql_error)",
                session.playerId,
            )
            ClaimSessionResult.Error
        }
    }

    /**
     * The claim used while session writes are queued (`grounds.player.async-writes`): the same one
     * statement as [claimSession], plus two terms.
     * - [endedSessionIds]: sessions this runtime has queued a logout for. Their row may still exist
     *   until the writer deletes it, and a reconnect in that window must not be told "already
     *   online" — the synchronous logout would have removed the row before it.
     * - `server_updated_at` restarts with the session, so the writer's ordering guard for server
     *   changes compares only events of the session that owns the row.
     */
    @Fresh
    fun claimSessionAsync(
        session: PlayerSession,
        staleBefore: Instant,
        endedSessionIds: Collection<UUID>,
    ): ClaimSessionResult {
        val sessionId = session.sessionId ?: return ClaimSessionResult.Error
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(CLAIM_SESSION_ASYNC).use { statement ->
                    statement.setObject(1, session.playerId)
                    statement.setTimestamp(2, Timestamp.from(session.connectedAt))
                    statement.setTimestamp(3, Timestamp.from(session.lastSeenAt))
                    statement.setString(4, session.playerName)
                    statement.setString(5, session.proxyId)
                    statement.setString(6, session.serverName)
                    statement.setString(7, session.region)
                    statement.setObject(8, sessionId)
                    statement.setTimestamp(9, Timestamp.from(staleBefore))
                    statement.setArray(
                        10,
                        connection.createArrayOf("uuid", endedSessionIds.toTypedArray()),
                    )
                    if (statement.executeUpdate() > 0) ClaimSessionResult.Claimed
                    else ClaimSessionResult.AlreadyOnline
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session claim failed (playerId=%s, reason=sql_error)",
                session.playerId,
            )
            ClaimSessionResult.Error
        }
    }

    /**
     * The writer's server change: exact session, and never older than what the row already holds.
     * UPDATE only — a change that arrives after its session ended finds no row (or a later
     * session's) and changes nothing, so a late event cannot resurrect a session.
     *
     * Throws on a database error: the writer redelivers, the synchronous fallback answers 503.
     */
    @Fresh
    fun applyServerChanged(
        playerId: UUID,
        sessionId: UUID,
        serverName: String,
        changedAt: Instant,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPDATE_SERVER_ORDERED).use { statement ->
                statement.setString(1, serverName)
                statement.setTimestamp(2, Timestamp.from(changedAt))
                statement.setObject(3, playerId)
                statement.setObject(4, sessionId)
                statement.setTimestamp(5, Timestamp.from(changedAt))
                statement.executeUpdate() > 0
            }
        }

    /** The writer's logout: exact session, a duplicate is a no-op. Throws on a database error. */
    @Fresh
    fun applySessionEnded(playerId: UUID, sessionId: UUID): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(DELETE_BY_PLAYER_AND_SESSION).use { statement ->
                statement.setObject(1, playerId)
                statement.setObject(2, sessionId)
                statement.executeUpdate() > 0
            }
        }

    @Fresh
    fun createHandoff(
        playerId: UUID,
        sessionId: UUID,
        tokenHash: ByteArray,
        expiresAt: Instant,
        activeAfter: Instant,
    ): CreateHandoffResult =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_HANDOFF_FOR_SESSION).use { statement ->
                    statement.setBytes(1, tokenHash)
                    statement.setTimestamp(2, Timestamp.from(expiresAt))
                    statement.setObject(3, playerId)
                    statement.setObject(4, sessionId)
                    statement.setTimestamp(5, Timestamp.from(activeAfter))
                    when (statement.executeUpdate()) {
                        1 -> CreateHandoffResult.Created
                        else -> CreateHandoffResult.NoActiveSession
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player handoff creation failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            CreateHandoffResult.Error
        }

    @Fresh
    fun consumeHandoff(
        tokenHash: ByteArray,
        playerId: UUID,
        sessionId: UUID,
        playerName: String?,
        proxyId: String?,
        region: String?,
        now: Instant,
        activeAfter: Instant,
    ): ConsumeHandoffResult =
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val sourceSessionId =
                        connection.prepareStatement(CONSUME_HANDOFF).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setBytes(2, tokenHash)
                            statement.setObject(3, playerId)
                            statement.setTimestamp(4, Timestamp.from(now))
                            statement.executeQuery().use { resultSet ->
                                if (resultSet.next()) {
                                    resultSet.getObject("source_session_id", UUID::class.java)
                                } else null
                            }
                        }
                    if (sourceSessionId == null) {
                        connection.rollback()
                        return@use ConsumeHandoffResult.NotFound
                    }

                    val updated =
                        connection.prepareStatement(CONSUME_SESSION_HANDOFF).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setTimestamp(2, Timestamp.from(now))
                            statement.setString(3, playerName)
                            statement.setString(4, proxyId)
                            statement.setString(5, region)
                            statement.setObject(6, sessionId)
                            statement.setObject(7, playerId)
                            statement.setObject(8, sourceSessionId)
                            statement.setTimestamp(9, Timestamp.from(activeAfter))
                            statement.setObject(10, sessionId)
                            statement.executeUpdate()
                        }
                    if (updated != 1) {
                        connection.rollback()
                        return@use ConsumeHandoffResult.NotFound
                    }
                    connection.commit()
                    ConsumeHandoffResult.Consumed
                } catch (error: SQLException) {
                    connection.rollback()
                    throw error
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(error, "Player handoff consumption failed (reason=sql_error)")
            ConsumeHandoffResult.Error
        }

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
    fun updateServerForSession(playerId: UUID, sessionId: UUID, serverName: String): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_SERVER_FOR_SESSION).use { statement ->
                    statement.setString(1, serverName)
                    statement.setObject(2, playerId)
                    statement.setObject(3, sessionId)
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

    @Fresh
    fun deleteSessionForSession(playerId: UUID, sessionId: UUID): DeleteSessionResult =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DELETE_BY_PLAYER_AND_SESSION).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setObject(2, sessionId)
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
    fun touchSessionsForIds(
        sessions: Collection<Pair<UUID, UUID>>,
        lastSeenAt: Instant,
    ): TouchSessionsResult {
        if (sessions.isEmpty()) return TouchSessionsResult.Updated(0)
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(UPDATE_LAST_SEEN_BY_SESSION_BATCH).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(lastSeenAt))
                    statement.setArray(
                        2,
                        connection.createArrayOf("uuid", sessions.map { it.first }.toTypedArray()),
                    )
                    statement.setArray(
                        3,
                        connection.createArrayOf("uuid", sessions.map { it.second }.toTypedArray()),
                    )
                    TouchSessionsResult.Updated(statement.executeUpdate())
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player session heartbeat failed (count=%d, reason=sql_error)",
                sessions.size,
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

    /**
     * What `player_playtime` has accumulated plus the segment the session is still in, so the
     * number ticks up while the player is online. The running segment stops at [sessionTtl] past
     * the last heartbeat, as the trigger that credits it does: a player on a crashed proxy stops
     * earning time even before the cleanup removes the row. Null on a database error.
     */
    @Fresh
    fun findPlaytime(playerId: UUID, sessionTtl: Duration): Playtime? =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(SELECT_PLAYTIME).use { statement ->
                    statement.setObject(1, playerId)
                    statement.setLong(2, sessionTtl.toMillis())
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        Playtime(
                            resultSet.getLong("total_millis"),
                            resultSet.getLong("play_millis"),
                        )
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player playtime read failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            null
        }

    /**
     * How many of [playerIds] hold a session. Like the other counts, a session whose proxy stopped
     * heartbeating still counts until the cleanup removes it. Null on a database error.
     */
    @Fresh
    fun countOnline(playerIds: Collection<UUID>): Int? =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(COUNT_ONLINE).use { statement ->
                    statement.setArray(
                        1,
                        connection.createArrayOf("uuid", playerIds.toTypedArray()),
                    )
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            }
        } catch (error: SQLException) {
            LOG.errorf(
                error,
                "Player online count failed (count=%d, reason=sql_error)",
                playerIds.size,
            )
            null
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
            resultSet.getObject("session_id", UUID::class.java),
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
            SELECT player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region, session_id
            FROM player_sessions
            WHERE player_id = ?
            """
        private const val SELECT_BY_NAME =
            """
            SELECT player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region, session_id
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
            WHERE player_id = ? AND session_id IS NULL
            """
        private const val UPDATE_SERVER_FOR_SESSION =
            """
            UPDATE player_sessions
            SET server_name = ?
            WHERE player_id = ? AND session_id = ?
            """
        private const val DELETE_BY_PLAYER_AND_SESSION =
            """
            DELETE FROM player_sessions
            WHERE player_id = ? AND session_id = ?
            """
        private const val CLAIM_SESSION =
            """
            INSERT INTO player_sessions (player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region, session_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_id) DO UPDATE
            SET connected_at = CASE
                    WHEN player_sessions.session_id = EXCLUDED.session_id THEN player_sessions.connected_at
                    ELSE EXCLUDED.connected_at
                END,
                last_seen_at = EXCLUDED.last_seen_at,
                player_name = EXCLUDED.player_name,
                proxy_id = EXCLUDED.proxy_id,
                server_name = EXCLUDED.server_name,
                region = EXCLUDED.region,
                session_id = EXCLUDED.session_id
            WHERE player_sessions.session_id = EXCLUDED.session_id
               OR player_sessions.last_seen_at < ?
            """
        private const val CLAIM_SESSION_ASYNC =
            """
            INSERT INTO player_sessions (player_id, connected_at, last_seen_at, player_name, proxy_id, server_name, region, session_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_id) DO UPDATE
            SET connected_at = CASE
                    WHEN player_sessions.session_id = EXCLUDED.session_id THEN player_sessions.connected_at
                    ELSE EXCLUDED.connected_at
                END,
                last_seen_at = EXCLUDED.last_seen_at,
                player_name = EXCLUDED.player_name,
                proxy_id = EXCLUDED.proxy_id,
                server_name = EXCLUDED.server_name,
                region = EXCLUDED.region,
                server_updated_at = CASE
                    WHEN player_sessions.session_id = EXCLUDED.session_id THEN player_sessions.server_updated_at
                    ELSE NULL
                END,
                session_id = EXCLUDED.session_id
            WHERE player_sessions.session_id = EXCLUDED.session_id
               OR player_sessions.last_seen_at < ?
               OR player_sessions.session_id = ANY(?)
            """
        private const val UPDATE_SERVER_ORDERED =
            """
            UPDATE player_sessions
            SET server_name = ?, server_updated_at = ?
            WHERE player_id = ? AND session_id = ?
              AND (server_updated_at IS NULL OR server_updated_at <= ?)
            """
        private const val INSERT_HANDOFF_FOR_SESSION =
            """
            INSERT INTO player_session_handoffs (token_hash, player_id, source_session_id, expires_at)
            SELECT ?, player_id, session_id, ?
            FROM player_sessions
            WHERE player_id = ? AND session_id = ? AND last_seen_at >= ?
            """
        private const val CONSUME_HANDOFF =
            """
            UPDATE player_session_handoffs
            SET consumed_at = ?
            WHERE token_hash = ? AND player_id = ? AND consumed_at IS NULL AND expires_at > ?
            RETURNING source_session_id
            """
        private const val CONSUME_SESSION_HANDOFF =
            """
            UPDATE player_sessions
            SET connected_at = ?, last_seen_at = ?, player_name = ?, proxy_id = ?, server_name = NULL,
                region = ?, session_id = ?
            WHERE player_id = ? AND session_id = ? AND last_seen_at >= ?
              AND session_id <> ?
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
            WHERE player_sessions.session_id IS NULL
            """
        private const val DELETE_BY_PLAYER =
            """
            DELETE FROM player_sessions
            WHERE player_id = ? AND session_id IS NULL
            """
        private const val DELETE_BY_PLAYER_AND_PROXY =
            """
            DELETE FROM player_sessions
            WHERE player_id = ? AND proxy_id = ? AND session_id IS NULL
            """
        private const val UPDATE_LAST_SEEN_BATCH =
            """
            UPDATE player_sessions
            SET last_seen_at = ?
            WHERE player_id = ANY(?) AND session_id IS NULL
            """
        private const val UPDATE_LAST_SEEN_BY_SESSION_BATCH =
            """
            UPDATE player_sessions AS current
            SET last_seen_at = ?
            FROM unnest(?::uuid[], ?::uuid[]) AS requested(player_id, session_id)
            WHERE current.player_id = requested.player_id
              AND current.session_id = requested.session_id
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

        /**
         * Always exactly one row: the requested id drives the joins, so a player who was never seen
         * reads as zero rather than as no row. `player_server_is_game` and the segment's shape are
         * the same ones the crediting trigger uses (V11).
         */
        private const val SELECT_PLAYTIME =
            """
            SELECT COALESCE(t.total_millis, 0) + COALESCE(live.millis, 0) AS total_millis,
                   COALESCE(t.play_millis, 0) + CASE WHEN live.game THEN live.millis ELSE 0 END AS play_millis
            FROM (SELECT ?::uuid AS player_id) AS requested
            LEFT JOIN player_playtime AS t ON t.player_id = requested.player_id
            LEFT JOIN (
                SELECT player_id,
                       GREATEST(
                           0,
                           (EXTRACT(EPOCH FROM LEAST(now(), last_seen_at + ?::bigint * INTERVAL '1 millisecond') - server_since) * 1000)::bigint
                       ) AS millis,
                       player_server_is_game(server_name) AS game
                FROM player_sessions
            ) AS live ON live.player_id = requested.player_id
            """
        private const val COUNT_ONLINE =
            """
            SELECT COUNT(*)
            FROM player_sessions
            WHERE player_id = ANY(?)
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
