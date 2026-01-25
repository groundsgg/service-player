package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerHeartbeatBatchReply
import gg.grounds.grpc.player.PlayerHeartbeatBatchRequest
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@GrpcService
@Blocking
class PlayerPresenceGrpcService
@Inject
constructor(private val repository: PlayerSessionRepository) : PlayerPresenceService {
    @ConfigProperty(name = "grounds.player.sessions.ttl", defaultValue = "90s")
    lateinit var sessionTtl: Duration

    override fun tryPlayerLogin(request: PlayerLoginRequest): Uni<PlayerLoginReply> {
        return Uni.createFrom().item { handleLogin(request) }
    }

    override fun playerLogout(request: PlayerLogoutRequest): Uni<PlayerLogoutReply> {
        return Uni.createFrom().item { handleLogout(request) }
    }

    override fun playerHeartbeatBatch(
        request: PlayerHeartbeatBatchRequest
    ): Uni<PlayerHeartbeatBatchReply> {
        return Uni.createFrom().item { handleHeartbeatBatch(request) }
    }

    private fun handleLogin(request: PlayerLoginRequest): PlayerLoginReply {
        val playerId =
            parsePlayerId(request.playerId)
                ?: return PlayerLoginReply.newBuilder()
                    .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                    .setMessage("player_id must be a UUID")
                    .build()

        val now = Instant.now()
        val session = PlayerSession(playerId, now, now)
        val inserted = repository.insertSession(session)
        if (inserted) {
            LOG.infof("Player session created (playerId=%s, result=accepted)", playerId)
            return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED)
                .setMessage("player accepted")
                .build()
        }

        val existing = repository.findByPlayerId(playerId)
        if (existing != null) {
            if (isStale(existing, now)) {
                val removed = repository.deleteSession(playerId)
                if (removed == DeleteSessionResult.ERROR) {
                    return PlayerLoginReply.newBuilder()
                        .setStatus(LoginStatus.LOGIN_STATUS_ERROR)
                        .setMessage("unable to remove stale player session")
                        .build()
                }
                if (removed == DeleteSessionResult.REMOVED) {
                    LOG.infof(
                        "Player session expired (playerId=%s, lastSeenAt=%s)",
                        playerId,
                        existing.lastSeenAt,
                    )
                }
                if (removed == DeleteSessionResult.NOT_FOUND) {
                    LOG.infof("Player session missing during stale cleanup (playerId=%s)", playerId)
                }
                if (repository.insertSession(session)) {
                    LOG.infof("Player session created (playerId=%s, result=accepted)", playerId)
                    return PlayerLoginReply.newBuilder()
                        .setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED)
                        .setMessage("player accepted")
                        .build()
                }
            }

            LOG.infof("Player session rejected (playerId=%s, reason=already_online)", playerId)
            return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE)
                .setMessage("player already online")
                .build()
        }

        LOG.errorf("Player session verification failed (playerId=%s)", playerId)
        return PlayerLoginReply.newBuilder()
            .setStatus(LoginStatus.LOGIN_STATUS_ERROR)
            .setMessage("unable to verify player session")
            .build()
    }

    private fun handleHeartbeatBatch(
        request: PlayerHeartbeatBatchRequest
    ): PlayerHeartbeatBatchReply {
        val playerIds =
            parsePlayerIds(request.playerIdsList)
                ?: return PlayerHeartbeatBatchReply.newBuilder()
                    .setUpdated(0)
                    .setMissing(0)
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
            .setMessage("heartbeat accepted")
            .build()
    }

    private fun handleLogout(request: PlayerLogoutRequest): PlayerLogoutReply {
        val playerId =
            parsePlayerId(request.playerId)
                ?: return PlayerLogoutReply.newBuilder()
                    .setRemoved(false)
                    .setMessage("player_id must be a UUID")
                    .build()

        return when (repository.deleteSession(playerId)) {
            DeleteSessionResult.REMOVED -> {
                LOG.infof("Player session removed (playerId=%s, result=logout)", playerId)
                PlayerLogoutReply.newBuilder().setRemoved(true).setMessage("player removed").build()
            }
            DeleteSessionResult.NOT_FOUND ->
                PlayerLogoutReply.newBuilder()
                    .setRemoved(false)
                    .setMessage("player session not found")
                    .build()
            DeleteSessionResult.ERROR ->
                PlayerLogoutReply.newBuilder()
                    .setRemoved(false)
                    .setMessage("unable to remove player session")
                    .build()
        }
    }

    private fun parsePlayerId(value: String?): UUID? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
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
        return if (parsed.any { it == null }) null else parsed.filterNotNull()
    }

    private fun isStale(session: PlayerSession, now: Instant): Boolean {
        return session.lastSeenAt.isBefore(now.minus(sessionTtl))
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPresenceGrpcService::class.java)
    }
}
