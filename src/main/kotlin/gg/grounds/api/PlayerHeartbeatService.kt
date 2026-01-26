package gg.grounds.api

import gg.grounds.grpc.player.PlayerHeartbeatBatchReply
import gg.grounds.grpc.player.PlayerHeartbeatBatchRequest
import gg.grounds.persistence.PlayerSessionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerHeartbeatService @Inject constructor(private val repository: PlayerSessionRepository) {
    fun handleHeartbeatBatch(request: PlayerHeartbeatBatchRequest): PlayerHeartbeatBatchReply {
        val playerIds =
            parsePlayerIds(request.playerIdsList)
                ?: return PlayerHeartbeatBatchReply.newBuilder()
                    .setUpdated(0)
                    .setMissing(0)
                    .setSuccess(false)
                    .setMessage("player_ids must be UUIDs")
                    .also {
                        LOG.warnf(
                            "Player heartbeat batch rejected (count=%d, reason=invalid_player_ids)",
                            request.playerIdsList.size,
                        )
                    }
                    .build()

        if (playerIds.isEmpty()) {
            LOG.debugf("Player heartbeat batch skipped (count=0, reason=empty_request)")
            return PlayerHeartbeatBatchReply.newBuilder()
                .setUpdated(0)
                .setMissing(0)
                .setSuccess(false)
                .setMessage("no player ids provided")
                .build()
        }

        val updated = repository.touchSessions(playerIds, Instant.now())
        val missing = (playerIds.size - updated).coerceAtLeast(0)
        LOG.debugf(
            "Player heartbeat batch processed (count=%d, updated=%d, missing=%d)",
            playerIds.size,
            updated,
            missing,
        )
        return PlayerHeartbeatBatchReply.newBuilder()
            .setUpdated(updated)
            .setMissing(missing)
            .setSuccess(true)
            .setMessage("heartbeat accepted")
            .build()
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

    companion object {
        private val LOG = Logger.getLogger(PlayerHeartbeatService::class.java)
    }
}
