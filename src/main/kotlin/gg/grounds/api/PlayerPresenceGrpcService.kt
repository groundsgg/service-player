package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.CountPlayersByProxyReply
import gg.grounds.grpc.player.CountPlayersByProxyRequest
import gg.grounds.grpc.player.CountPlayersByServerReply
import gg.grounds.grpc.player.CountPlayersByServerRequest
import gg.grounds.grpc.player.GetPlayerLocaleReply
import gg.grounds.grpc.player.GetPlayerLocaleRequest
import gg.grounds.grpc.player.GetPlayerSessionReply
import gg.grounds.grpc.player.GetPlayerSessionRequest
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.LookupPlayerNamesReply
import gg.grounds.grpc.player.LookupPlayerNamesRequest
import gg.grounds.grpc.player.PlayerHeartbeatBatchReply
import gg.grounds.grpc.player.PlayerHeartbeatBatchRequest
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.grpc.player.PlayerSessionInfo
import gg.grounds.grpc.player.ProxyPlayerCount as GrpcProxyPlayerCount
import gg.grounds.grpc.player.ResolvePlayerNameReply
import gg.grounds.grpc.player.ResolvePlayerNameRequest
import gg.grounds.grpc.player.ServerPlayerCount as GrpcServerPlayerCount
import gg.grounds.grpc.player.SetPlayerLocaleReply
import gg.grounds.grpc.player.SetPlayerLocaleRequest
import gg.grounds.grpc.player.SuggestPlayerNamesReply
import gg.grounds.grpc.player.SuggestPlayerNamesRequest
import gg.grounds.grpc.player.UpdatePlayerServerReply
import gg.grounds.grpc.player.UpdatePlayerServerRequest
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.presence.PlayerHeartbeatService
import gg.grounds.presence.PlayerHeartbeatService.HeartbeatOutcome
import gg.grounds.presence.PresenceService
import gg.grounds.presence.PresenceService.LoginOutcome
import gg.grounds.presence.PresenceService.LogoutOutcome
import io.grpc.Status
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject

/**
 * The gRPC face of the presence API, kept only until `plugin-player` and `plugin-match` have moved
 * to the HTTP API this service now publishes.
 *
 * It holds no rules of its own: every method maps a protobuf message onto [PresenceService] and its
 * answer back, so retiring gRPC is a subtraction rather than a rewrite. Where a message names a
 * field — `player_id`, `player_ids` — the wording is produced here, because the same outcome is
 * spelled `playerId` in JSON.
 */
@GrpcService
@Blocking
class PlayerPresenceGrpcService
@Inject
constructor(
    private val presence: PresenceService,
    private val heartbeatService: PlayerHeartbeatService,
) : PlayerPresenceService {

    override fun tryPlayerLogin(request: PlayerLoginRequest): Uni<PlayerLoginReply> =
        Uni.createFrom().item {
            val reply = PlayerLoginReply.newBuilder()
            when (
                val outcome =
                    presence.login(
                        playerId = request.playerId,
                        playerName = request.playerName,
                        proxyId = request.proxyId,
                        region = request.region,
                    )
            ) {
                LoginOutcome.Accepted ->
                    reply.setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED).setMessage("player accepted")
                LoginOutcome.AlreadyOnline ->
                    reply
                        .setStatus(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE)
                        .setMessage("player already online")
                LoginOutcome.InvalidPlayerId ->
                    reply
                        .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                        .setMessage("player_id must be a UUID")
                is LoginOutcome.Failed ->
                    reply.setStatus(LoginStatus.LOGIN_STATUS_ERROR).setMessage(outcome.message)
            }
            reply.build()
        }

    override fun playerLogout(request: PlayerLogoutRequest): Uni<PlayerLogoutReply> =
        Uni.createFrom().item {
            val reply = PlayerLogoutReply.newBuilder()
            when (val outcome = presence.logout(request.playerId, request.proxyId)) {
                LogoutOutcome.Removed -> reply.setRemoved(true).setMessage("player removed")
                LogoutOutcome.NotFound ->
                    reply.setRemoved(false).setMessage("player session not found")
                LogoutOutcome.InvalidPlayerId ->
                    reply.setRemoved(false).setMessage("player_id must be a UUID")
                is LogoutOutcome.Failed -> reply.setRemoved(false).setMessage(outcome.message)
            }
            reply.build()
        }

    override fun playerHeartbeatBatch(
        request: PlayerHeartbeatBatchRequest
    ): Uni<PlayerHeartbeatBatchReply> =
        Uni.createFrom().item {
            val reply = PlayerHeartbeatBatchReply.newBuilder()
            when (val outcome = heartbeatService.handleHeartbeatBatch(request.playerIdsList)) {
                is HeartbeatOutcome.Accepted ->
                    reply
                        .setUpdated(outcome.updated)
                        .setMissing(outcome.missing)
                        .setSuccess(true)
                        .setMessage("heartbeat accepted")
                is HeartbeatOutcome.Rejected ->
                    reply
                        .setUpdated(0)
                        .setMissing(0)
                        .setSuccess(false)
                        .setMessage(
                            when (outcome.reason) {
                                HeartbeatOutcome.Reason.INVALID_PLAYER_IDS ->
                                    "player_ids must be UUIDs"
                                HeartbeatOutcome.Reason.EMPTY -> "no player ids provided"
                            }
                        )
                is HeartbeatOutcome.Failed ->
                    reply
                        .setUpdated(0)
                        .setMissing(outcome.missing)
                        .setSuccess(false)
                        .setMessage(outcome.message)
            }
            reply.build()
        }

    override fun getPlayerSession(request: GetPlayerSessionRequest): Uni<GetPlayerSessionReply> =
        Uni.createFrom().item {
            val session = presence.findSession(request.playerId)
            val reply = GetPlayerSessionReply.newBuilder().setFound(session != null)
            if (session != null) {
                reply.setSession(toSessionInfo(session))
            }
            reply.build()
        }

    override fun resolvePlayerName(request: ResolvePlayerNameRequest): Uni<ResolvePlayerNameReply> =
        Uni.createFrom().item {
            val session = presence.resolveName(request.playerName)
            val reply = ResolvePlayerNameReply.newBuilder().setFound(session != null)
            if (session != null) {
                reply.setSession(toSessionInfo(session))
            }
            reply.build()
        }

    override fun suggestPlayerNames(
        request: SuggestPlayerNamesRequest
    ): Uni<SuggestPlayerNamesReply> =
        Uni.createFrom().item {
            SuggestPlayerNamesReply.newBuilder()
                .addAllPlayerNames(presence.suggestNames(request.prefix, request.limit))
                .build()
        }

    override fun updatePlayerServer(
        request: UpdatePlayerServerRequest
    ): Uni<UpdatePlayerServerReply> =
        Uni.createFrom().item {
            UpdatePlayerServerReply.newBuilder()
                .setUpdated(presence.updateServer(request.playerId, request.serverName))
                .build()
        }

    override fun getPlayerLocale(request: GetPlayerLocaleRequest): Uni<GetPlayerLocaleReply> =
        Uni.createFrom().item {
            // Absent is an empty tag on the wire: proto3 has no null, and the caller falls back to
            // the client's announced locale either way.
            GetPlayerLocaleReply.newBuilder()
                .setLocale(presence.getLocale(request.playerId) ?: "")
                .build()
        }

    override fun setPlayerLocale(request: SetPlayerLocaleRequest): Uni<SetPlayerLocaleReply> =
        Uni.createFrom().item {
            SetPlayerLocaleReply.newBuilder()
                .setUpdated(presence.setLocale(request.playerId, request.locale))
                .build()
        }

    override fun lookupPlayerNames(request: LookupPlayerNamesRequest): Uni<LookupPlayerNamesReply> =
        Uni.createFrom().item {
            LookupPlayerNamesReply.newBuilder()
                .putAllNames(
                    presence.lookupNames(request.playerIdsList).mapKeys { (playerId, _) ->
                        playerId.toString()
                    }
                )
                .build()
        }

    override fun countPlayersByServer(
        request: CountPlayersByServerRequest
    ): Uni<CountPlayersByServerReply> =
        Uni.createFrom().item {
            val counts = countOrUnavailable { presence.countPlayersByServer() }
            CountPlayersByServerReply.newBuilder()
                .addAllServers(counts.servers.map(::toServerPlayerCount))
                .setTotal(counts.total)
                .build()
        }

    override fun countPlayersByProxy(
        request: CountPlayersByProxyRequest
    ): Uni<CountPlayersByProxyReply> =
        Uni.createFrom().item {
            val counts = countOrUnavailable { presence.countPlayersByProxy() }
            CountPlayersByProxyReply.newBuilder()
                .addAllProxies(counts.proxies.map(::toProxyPlayerCount))
                .setTotal(counts.total)
                .build()
        }

    /**
     * A count the store could not answer fails the call rather than reporting zero — an empty reply
     * reads as "nobody is online anywhere", which callers will happily render.
     */
    private fun <T> countOrUnavailable(read: () -> T): T =
        try {
            read()
        } catch (unavailable: PresenceService.PresenceUnavailableException) {
            throw Status.UNAVAILABLE.withDescription(unavailable.message).asRuntimeException()
        }

    private fun toServerPlayerCount(
        count: PlayerSessionRepository.ServerPlayerCount
    ): GrpcServerPlayerCount =
        GrpcServerPlayerCount.newBuilder()
            .setServerName(count.serverName)
            .setPlayers(count.players)
            .build()

    private fun toProxyPlayerCount(
        count: PlayerSessionRepository.ProxyPlayerCount
    ): GrpcProxyPlayerCount =
        GrpcProxyPlayerCount.newBuilder()
            .setProxyId(count.proxyId)
            .setRegion(count.region ?: "")
            .setPlayers(count.players)
            .build()

    private fun toSessionInfo(session: PlayerSession): PlayerSessionInfo =
        PlayerSessionInfo.newBuilder()
            .setPlayerId(session.playerId.toString())
            .setPlayerName(session.playerName ?: "")
            .setProxyId(session.proxyId ?: "")
            .setServerName(session.serverName ?: "")
            .setConnectedAtMillis(session.connectedAt.toEpochMilli())
            .setRegion(session.region ?: "")
            .build()
}
