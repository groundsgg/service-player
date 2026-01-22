package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.LoginStatus
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
import java.time.Instant
import java.util.UUID
import org.jboss.logging.Logger

@GrpcService
@Blocking
class PlayerPresenceGrpcService
@Inject
constructor(private val repository: PlayerSessionRepository) : PlayerPresenceService {
    override fun tryPlayerLogin(request: PlayerLoginRequest): Uni<PlayerLoginReply> {
        return Uni.createFrom().item { handleLogin(request) }
    }

    override fun playerLogout(request: PlayerLogoutRequest): Uni<PlayerLogoutReply> {
        return Uni.createFrom().item { handleLogout(request) }
    }

    private fun handleLogin(request: PlayerLoginRequest): PlayerLoginReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Player login request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return PlayerLoginReply.newBuilder()
                        .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                        .setMessage("player_id must be a UUID")
                        .build()
                }

        val session = PlayerSession(playerId, Instant.now())
        val inserted = repository.insertSession(session)
        if (inserted) {
            LOG.infof("Created player session successfully (playerId=%s)", playerId)
            return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED)
                .setMessage("player accepted")
                .build()
        }

        val existing = repository.findByPlayerId(playerId)
        if (existing != null) {
            LOG.warnf(
                "Player login request rejected because session already exists (playerId=%s)",
                playerId,
            )
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
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Player logout request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return PlayerLogoutReply.newBuilder()
                        .setRemoved(false)
                        .setMessage("player_id must be a UUID")
                        .build()
                }

        return when (repository.deleteSession(playerId)) {
            DeleteSessionResult.REMOVED -> {
                LOG.infof("Removed player session successfully (playerId=%s)", playerId)
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

    companion object {
        private val LOG = Logger.getLogger(PlayerPresenceGrpcService::class.java)
    }
}
