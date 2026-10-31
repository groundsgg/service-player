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

/** Shared database authority across proxies and service replicas. */
@ApplicationScoped
class TransferAdmissionRepository @Inject constructor(private val dataSource: DataSource) {
    sealed interface Result {
        data class Admitted(val expiresAt: Instant) : Result

        data object Unavailable : Result

        data object Error : Result
    }

    @Fresh
    fun reserve(
        playerId: UUID,
        tokenHash: ByteArray,
        connectionId: UUID,
        proxyId: String,
        now: Instant,
        activeAfter: Instant,
    ): Result =
        try {
            dataSource.connection.use { db ->
                db.autoCommit = false
                try {
                    db.createStatement().use {
                        it.execute("SET LOCAL statement_timeout = '2s'")
                        it.execute("SET LOCAL lock_timeout = '1s'")
                    }
                    // Consume takes the same session lock first. Sibling tokens cannot race
                    // admission against admission OR the old, unbound consume endpoint.
                    val source =
                        db.prepareStatement(
                                "SELECT session_id FROM player_sessions WHERE player_id = ? AND last_seen_at >= ? FOR UPDATE"
                            )
                            .use { query ->
                                query.setObject(1, playerId)
                                query.setTimestamp(2, Timestamp.from(activeAfter))
                                query.executeQuery().use {
                                    if (it.next()) it.getObject(1, UUID::class.java) else null
                                }
                            }
                    if (source == null) {
                        db.rollback()
                        return@use Result.Unavailable
                    }
                    // Expired tickets can never complete. A newly issued ticket for the still
                    // authenticated source may reserve after expiry; never rebind the old one.
                    db.prepareStatement(
                            "DELETE FROM player_session_handoffs WHERE player_id = ? AND expires_at <= ?"
                        )
                        .use {
                            it.setObject(1, playerId)
                            it.setTimestamp(2, Timestamp.from(now))
                            it.executeUpdate()
                        }
                    // A bounded destination budget must hold across service replicas and all
                    // source proxies. Acquire after the source lock in every reservation.
                    db.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                        .use {
                            it.setString(1, "grounds-transfer-admission:$proxyId")
                            it.execute()
                        }
                    val expires =
                        db.prepareStatement(
                                """
                                UPDATE player_session_handoffs AS candidate
                                SET admission_connection_id = ?, admission_proxy_id = ?
                                WHERE token_hash = ? AND player_id = ? AND source_session_id = ?
                                  AND consumed_at IS NULL AND admission_closed_at IS NULL AND expires_at > GREATEST(?, clock_timestamp())
                                  AND (
                                    (admission_connection_id = ? AND admission_proxy_id = ?)
                                    OR (admission_connection_id IS NULL
                                      AND NOT EXISTS (
                                        SELECT 1 FROM player_session_handoffs AS other
                                        WHERE other.player_id = candidate.player_id
                                          AND other.source_session_id = candidate.source_session_id
                                          AND other.admission_connection_id IS NOT NULL
                                      )
                                      AND (SELECT count(*) FROM player_session_handoffs
                                           WHERE admission_proxy_id = ? AND consumed_at IS NULL AND expires_at > ?) < 32
                                    )
                                  ) RETURNING expires_at
                                """
                                    .trimIndent()
                            )
                            .use {
                                it.setObject(1, connectionId)
                                it.setString(2, proxyId)
                                it.setBytes(3, tokenHash)
                                it.setObject(4, playerId)
                                it.setObject(5, source)
                                it.setTimestamp(6, Timestamp.from(now))
                                it.setObject(7, connectionId)
                                it.setString(8, proxyId)
                                it.setString(9, proxyId)
                                it.setTimestamp(10, Timestamp.from(now))
                                it.executeQuery().use { row ->
                                    if (row.next()) row.getTimestamp(1).toInstant() else null
                                }
                            }
                    db.commit()
                    if (expires == null) Result.Unavailable else Result.Admitted(expires)
                } catch (error: SQLException) {
                    db.rollback()
                    throw error
                }
            }
        } catch (error: SQLException) {
            LOG.error("Transfer admission failed (reason=sql_error)", error)
            Result.Error
        }

    enum class AbortResult {
        Closed,
        Invalid,
        Error,
    }

    @Fresh
    fun abort(
        playerId: UUID,
        tokenHash: ByteArray,
        connectionId: UUID,
        proxyId: String,
    ): AbortResult =
        try {
            dataSource.connection.use { db ->
                db.autoCommit = false
                try {
                    db.createStatement().use {
                        it.execute("SET LOCAL statement_timeout = '2s'")
                        it.execute("SET LOCAL lock_timeout = '1s'")
                    }
                    db.prepareStatement(
                            "SELECT session_id FROM player_sessions WHERE player_id = ? FOR UPDATE"
                        )
                        .use {
                            it.setObject(1, playerId)
                            it.executeQuery().close()
                        }
                    val matched =
                        db.prepareStatement(
                                """
                                UPDATE player_session_handoffs SET admission_closed_at = COALESCE(admission_closed_at, CURRENT_TIMESTAMP)
                                WHERE player_id = ? AND token_hash = ? AND admission_connection_id = ? AND admission_proxy_id = ?
                                """
                                    .trimIndent()
                            )
                            .use {
                                it.setObject(1, playerId)
                                it.setBytes(2, tokenHash)
                                it.setObject(3, connectionId)
                                it.setString(4, proxyId)
                                it.executeUpdate() > 0
                            }
                    if (matched)
                        db.prepareStatement(
                                "DELETE FROM player_sessions WHERE player_id = ? AND session_id = ?"
                            )
                            .use {
                                it.setObject(1, playerId)
                                it.setObject(2, connectionId)
                                it.executeUpdate()
                            }
                    db.commit()
                    AbortResult.Closed
                } catch (error: SQLException) {
                    db.rollback()
                    throw error
                }
            }
        } catch (error: SQLException) {
            LOG.error("Transfer admission abort failed (reason=sql_error)", error)
            AbortResult.Error
        }

    companion object {
        private val LOG = Logger.getLogger(TransferAdmissionRepository::class.java)
    }
}
