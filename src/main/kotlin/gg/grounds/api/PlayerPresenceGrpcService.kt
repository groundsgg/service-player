package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.GetPlayerSessionReply
import gg.grounds.grpc.player.GetPlayerSessionRequest
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerHeartbeatBatchReply
import gg.grounds.grpc.player.PlayerHeartbeatBatchRequest
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.grpc.player.PlayerSessionInfo
import gg.grounds.grpc.player.ResolvePlayerNameReply
import gg.grounds.grpc.player.ResolvePlayerNameRequest
import gg.grounds.grpc.player.SuggestPlayerNamesReply
import gg.grounds.grpc.player.SuggestPlayerNamesRequest
import gg.grounds.grpc.player.UpdatePlayerServerReply
import gg.grounds.grpc.player.UpdatePlayerServerRequest
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@GrpcService
@Blocking
class PlayerPresenceGrpcService
@Inject
constructor(
    private val repository: PlayerSessionRepository,
    private val heartbeatService: PlayerHeartbeatService,
) : PlayerPresenceService {
    @ConfigProperty(name = "grounds.player.sessions.ttl", defaultValue = "90s")
    private lateinit var sessionTtl: Duration

    override fun tryPlayerLogin(request: PlayerLoginRequest): Uni<PlayerLoginReply> {
        return Uni.createFrom().item { handleLogin(request) }
    }

    override fun playerLogout(request: PlayerLogoutRequest): Uni<PlayerLogoutReply> {
        return Uni.createFrom().item { handleLogout(request) }
    }

    override fun playerHeartbeatBatch(
        request: PlayerHeartbeatBatchRequest
    ): Uni<PlayerHeartbeatBatchReply> {
        return Uni.createFrom().item { heartbeatService.handleHeartbeatBatch(request) }
    }

    /**
     * Who and where a player is. A proxy calls this for someone who is not connected to it — it has
     * no other way to know they exist.
     *
     * Absent player, unknown id, unparseable uuid: `found = false`, never an error status. The
     * callers are Velocity command handlers; an exception here lands on the event loop.
     */
    override fun getPlayerSession(request: GetPlayerSessionRequest): Uni<GetPlayerSessionReply> {
        return Uni.createFrom().item { handleGetPlayerSession(request) }
    }

    /**
     * Name to session — the lookup behind `/msg <name>` and `/party invite <name>` when the target
     * is on another proxy. Case-insensitive: Minecraft names are unique, their casing is not.
     */
    override fun resolvePlayerName(request: ResolvePlayerNameRequest): Uni<ResolvePlayerNameReply> {
        return Uni.createFrom().item { handleResolvePlayerName(request) }
    }

    /**
     * Tab-complete. A prefix search with a hard cap ([MAX_SUGGEST_LIMIT]) — deliberately NOT a list
     * of everyone online: Velocity fires tab-complete on every keystroke, so at 10k players a
     * roster would be a large response sent thousands of times a second, scanning the table each
     * time. A blank prefix returns nothing rather than everything, for the same reason.
     */
    override fun suggestPlayerNames(
        request: SuggestPlayerNamesRequest
    ): Uni<SuggestPlayerNamesReply> {
        return Uni.createFrom().item { handleSuggestPlayerNames(request) }
    }

    /**
     * Follows a player across backend servers, so a session says where they actually are — a party
     * warp needs the target's server, and only the proxy holding them knows when it changes.
     */
    override fun updatePlayerServer(
        request: UpdatePlayerServerRequest
    ): Uni<UpdatePlayerServerReply> {
        return Uni.createFrom().item { handleUpdatePlayerServer(request) }
    }

    private fun handleLogin(request: PlayerLoginRequest): PlayerLoginReply {
        val playerId =
            parsePlayerId(request.playerId)
                ?: return PlayerLoginReply.newBuilder()
                    .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                    .setMessage("player_id must be a UUID")
                    .build()

        val now = Instant.now()
        val session =
            PlayerSession(
                playerId,
                now,
                now,
                blankToNull(request.playerName),
                blankToNull(request.proxyId),
            )
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
                    LOG.errorf(
                        "Player stale session cleanup failed (playerId=%s, lastSeenAt=%s, reason=delete_failed)",
                        playerId,
                        existing.lastSeenAt,
                    )
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
                val recreated = repository.findByPlayerId(playerId)
                if (recreated == null) {
                    LOG.errorf(
                        "Player session recreation failed (playerId=%s, reason=insert_failed)",
                        playerId,
                    )
                    return PlayerLoginReply.newBuilder()
                        .setStatus(LoginStatus.LOGIN_STATUS_ERROR)
                        .setMessage("unable to create player session after stale cleanup")
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
            DeleteSessionResult.ERROR -> {
                LOG.errorf(
                    "Player session removal failed (playerId=%s, reason=delete_failed)",
                    playerId,
                )
                PlayerLogoutReply.newBuilder()
                    .setRemoved(false)
                    .setMessage("unable to remove player session")
                    .build()
            }
        }
    }

    private fun handleGetPlayerSession(request: GetPlayerSessionRequest): GetPlayerSessionReply {
        val playerId =
            parsePlayerId(request.playerId)
                ?: return GetPlayerSessionReply.newBuilder().setFound(false).build()

        val session =
            repository.findByPlayerId(playerId)
                ?: return GetPlayerSessionReply.newBuilder().setFound(false).build()

        return GetPlayerSessionReply.newBuilder()
            .setFound(true)
            .setSession(toSessionInfo(session))
            .build()
    }

    private fun handleResolvePlayerName(request: ResolvePlayerNameRequest): ResolvePlayerNameReply {
        val name =
            blankToNull(request.playerName)
                ?: return ResolvePlayerNameReply.newBuilder().setFound(false).build()

        val session =
            repository.findByName(name)
                ?: return ResolvePlayerNameReply.newBuilder().setFound(false).build()

        return ResolvePlayerNameReply.newBuilder()
            .setFound(true)
            .setSession(toSessionInfo(session))
            .build()
    }

    private fun handleSuggestPlayerNames(
        request: SuggestPlayerNamesRequest
    ): SuggestPlayerNamesReply {
        val prefix =
            blankToNull(request.prefix) ?: return SuggestPlayerNamesReply.newBuilder().build()

        val names = repository.suggestNames(prefix, cappedSuggestLimit(request.limit))
        return SuggestPlayerNamesReply.newBuilder().addAllPlayerNames(names).build()
    }

    private fun handleUpdatePlayerServer(
        request: UpdatePlayerServerRequest
    ): UpdatePlayerServerReply {
        val playerId =
            parsePlayerId(request.playerId)
                ?: return UpdatePlayerServerReply.newBuilder().setUpdated(false).build()
        val serverName =
            blankToNull(request.serverName)
                ?: return UpdatePlayerServerReply.newBuilder().setUpdated(false).build()

        return UpdatePlayerServerReply.newBuilder()
            .setUpdated(repository.updateServer(playerId, serverName))
            .build()
    }

    private fun toSessionInfo(session: PlayerSession): PlayerSessionInfo {
        return PlayerSessionInfo.newBuilder()
            .setPlayerId(session.playerId.toString())
            .setPlayerName(session.playerName ?: "")
            .setProxyId(session.proxyId ?: "")
            .setServerName(session.serverName ?: "")
            .setConnectedAtMillis(session.connectedAt.toEpochMilli())
            .build()
    }

    private fun blankToNull(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parsePlayerId(value: String?): UUID? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun isStale(session: PlayerSession, now: Instant): Boolean {
        return session.lastSeenAt.isBefore(now.minus(sessionTtl))
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPresenceGrpcService::class.java)

        private const val MAX_SUGGEST_LIMIT = 25

        /** Clamps an untrusted client-supplied limit; `<= 0` falls back to the maximum. */
        internal fun cappedSuggestLimit(limit: Int): Int {
            return if (limit <= 0) MAX_SUGGEST_LIMIT else min(limit, MAX_SUGGEST_LIMIT)
        }
    }
}
