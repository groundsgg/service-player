package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.persistence.PlayerSessionRepository
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

@GrpcService
@Blocking
class PlayerPresenceGrpcService : PlayerPresenceService {
    @Inject
    lateinit var repository: PlayerSessionRepository

    override fun tryPlayerLogin(request: PlayerLoginRequest): Uni<PlayerLoginReply> {
        return Uni.createFrom().item { handleLogin(request) }
    }

    override fun playerLogout(request: PlayerLogoutRequest): Uni<PlayerLogoutReply> {
        return Uni.createFrom().item { handleLogout(request) }
    }

    private fun handleLogin(request: PlayerLoginRequest): PlayerLoginReply {
        val playerId = parsePlayerId(request.playerId)
            ?: return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                .setMessage("player_id must be a UUID")
                .build()

        val session = PlayerSession(playerId, Instant.now())
        val inserted = repository.insertSession(session)
        if (inserted) {
            LOG.infof("Player %s logged in", playerId)
            return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED)
                .setMessage("player accepted")
                .build()
        }

        val existing = repository.findByPlayerId(playerId)
        if (existing.isPresent) {
            LOG.infof("Player %s rejected: already online", playerId)
            return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE)
                .setMessage("player already online")
                .build()
        }

        return PlayerLoginReply.newBuilder()
            .setStatus(LoginStatus.LOGIN_STATUS_ERROR)
            .setMessage("unable to verify player session")
            .build()
    }

    private fun handleLogout(request: PlayerLogoutRequest): PlayerLogoutReply {
        val playerId = parsePlayerId(request.playerId)
            ?: return PlayerLogoutReply.newBuilder()
                .setRemoved(false)
                .setMessage("player_id must be a UUID")
                .build()

        val removed = repository.deleteSession(playerId)
        if (removed) {
            LOG.infof("Player %s logged out", playerId)
        }

        return PlayerLogoutReply.newBuilder()
            .setRemoved(removed)
            .setMessage(if (removed) "player removed" else "player session not found")
            .build()
    }

    private fun parsePlayerId(value: String?): UUID? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching { UUID.fromString(trimmed) }.getOrNull()
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPresenceGrpcService::class.java)
    }
}
