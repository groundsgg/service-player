package gg.grounds.presence

import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.TouchSessionsResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.jboss.logging.Logger

/**
 * Keeps sessions alive.
 *
 * Batched because a proxy holding a thousand players would otherwise send a thousand calls per
 * interval; one call per proxy per tick is the whole point of the shape.
 */
@ApplicationScoped
class PlayerHeartbeatService @Inject constructor(private val repository: PlayerSessionRepository) {

    /**
     * Touches every session named in [playerIds].
     *
     * All-or-nothing on parsing: one malformed id rejects the batch rather than silently touching
     * the rest, because a caller sending garbage has a bug and a partial success hides it.
     */
    fun handleHeartbeatBatch(playerIds: List<String>): HeartbeatOutcome {
        val ids =
            parsePlayerIds(playerIds)
                ?: run {
                    LOG.warnf(
                        "Player heartbeat batch rejected (count=%d, reason=invalid_player_ids)",
                        playerIds.size,
                    )
                    return HeartbeatOutcome.Rejected(HeartbeatOutcome.Reason.INVALID_PLAYER_IDS)
                }

        if (ids.isEmpty()) {
            LOG.debugf("Player heartbeat batch skipped (count=0, reason=empty_request)")
            return HeartbeatOutcome.Rejected(HeartbeatOutcome.Reason.EMPTY)
        }

        return when (val result = repository.touchSessions(ids, Instant.now())) {
            is TouchSessionsResult.Updated -> {
                val missing = (ids.size - result.count).coerceAtLeast(0)
                LOG.debugf(
                    "Player heartbeat batch processed (count=%d, updated=%d, missing=%d)",
                    ids.size,
                    result.count,
                    missing,
                )
                HeartbeatOutcome.Accepted(updated = result.count, missing = missing)
            }
            TouchSessionsResult.Error ->
                HeartbeatOutcome.Failed(
                    missing = ids.size,
                    message = "unable to update player sessions",
                )
        }
    }

    private fun parsePlayerIds(values: List<String>): List<UUID>? {
        if (values.isEmpty()) {
            return emptyList()
        }
        val trimmed = values.map { it.trim() }
        if (trimmed.any { it.isEmpty() }) {
            return null
        }
        val parsed = trimmed.map { runCatching { UUID.fromString(it) }.getOrNull() }
        return parsed.takeIf { parsedIds -> parsedIds.none { it == null } }?.filterNotNull()
    }

    sealed interface HeartbeatOutcome {
        /** Sessions were touched. [missing] counts ids with no session — a logout we raced. */
        data class Accepted(val updated: Int, val missing: Int) : HeartbeatOutcome

        /**
         * The request itself was not usable; nothing was touched. The reason is an enum rather than
         * a sentence because each transport names the field its own way.
         */
        data class Rejected(val reason: Reason) : HeartbeatOutcome

        /** The store could not be written. Nothing was touched. */
        data class Failed(val missing: Int, val message: String) : HeartbeatOutcome

        enum class Reason {
            INVALID_PLAYER_IDS,
            EMPTY,
        }
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerHeartbeatService::class.java)
    }
}
