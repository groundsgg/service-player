package gg.grounds.rest

import gg.grounds.domain.PlayerSession
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.presence.PresenceService
import java.time.Instant
import org.eclipse.microprofile.openapi.annotations.media.Schema

@Schema(
    name = "LoginRequest",
    description = "A proxy claiming the network-wide session for a player.",
)
data class LoginRequest(
    @get:Schema(
        description = "The player's Minecraft UUID.",
        required = true,
        examples = ["8f3a1c2e-4b5d-4e6f-8a9b-0c1d2e3f4a5b"],
    )
    val playerId: String?,
    @get:Schema(
        description =
            "The name the player logged in with. Optional, but a session created without one " +
                "cannot be found by name until the player reconnects.",
        examples = ["Notch"],
    )
    val playerName: String? = null,
    @get:Schema(
        description =
            "The proxy the player connected to. Optional, and worth sending: a login that " +
                "names its proxy can take a session over from a different one, which is what a " +
                "proxy-to-proxy transfer looks like from here. A login without it can only wait " +
                "out the session TTL.",
        examples = ["velocity-nl-ams1-7f9c"],
    )
    val proxyId: String? = null,
    @get:Schema(
        description = "Where that proxy is. Absent is a legitimate answer, not an error.",
        examples = ["nl-ams1"],
    )
    val region: String? = null,
)

@Schema(name = "PlayerSession", description = "A player's live session: who they are and where.")
data class PlayerSessionResponse(
    val playerId: String,
    @get:Schema(
        description = "Absent for a session created by a proxy that sent no name.",
        examples = ["Notch"],
    )
    val playerName: String?,
    @get:Schema(
        description =
            "The proxy holding the player. Absent for sessions created before proxies declared one.",
        examples = ["velocity-nl-ams1-7f9c"],
    )
    val proxyId: String?,
    @get:Schema(
        description =
            "The backend server the player is on. Absent while they are still on the proxy.",
        examples = ["lobby-2"],
    )
    val serverName: String?,
    @get:Schema(
        description =
            "Where the proxy is. Absent means unknown, which is normal — sessions outlive a rollout.",
        examples = ["nl-ams1"],
    )
    val region: String?,
    @get:Schema(description = "When the session was created.") val connectedAt: Instant,
) {
    companion object {
        fun from(session: PlayerSession) =
            PlayerSessionResponse(
                playerId = session.playerId.toString(),
                playerName = session.playerName,
                proxyId = session.proxyId,
                serverName = session.serverName,
                region = session.region,
                connectedAt = session.connectedAt,
            )
    }
}

@Schema(
    name = "HeartbeatRequest",
    description =
        "Every player a proxy still holds, in one call. Batched because a proxy with a thousand " +
            "players would otherwise send a thousand requests per interval.",
)
data class HeartbeatRequest(
    @get:Schema(description = "Player UUIDs. One malformed id rejects the whole batch.")
    val playerIds: List<String> = emptyList()
)

@Schema(name = "HeartbeatResponse", description = "What the batch did.")
data class HeartbeatResponse(
    @get:Schema(description = "Sessions touched.") val updated: Int,
    @get:Schema(
        description =
            "Ids with no session. Not an error: the player logged out between the proxy " +
                "building this batch and the write landing."
    )
    val missing: Int,
)

@Schema(name = "UpdateServerRequest", description = "The backend server a player has moved to.")
data class UpdateServerRequest(
    @get:Schema(required = true, examples = ["lobby-2"]) val serverName: String?
)

@Schema(
    name = "NameLookupResponse",
    description = "Player id to name, for players who need not be online.",
)
data class NameLookupResponse(
    @get:Schema(
        description =
            "Ids this service has never seen are absent rather than mapped to a placeholder " +
                "every caller would have to recognise."
    )
    val names: Map<String, String>
) {
    companion object {
        fun from(names: Map<java.util.UUID, String>) =
            NameLookupResponse(names.mapKeys { (playerId, _) -> playerId.toString() })
    }
}

@Schema(name = "NameSuggestionsResponse", description = "Tab-complete candidates, capped.")
data class NameSuggestionsResponse(val playerNames: List<String>)

@Schema(name = "ServerPlayerCount", description = "One backend server's share of the network.")
data class ServerPlayerCountResponse(val serverName: String, val players: Int) {
    companion object {
        fun from(count: PlayerSessionRepository.ServerPlayerCount) =
            ServerPlayerCountResponse(count.serverName, count.players)
    }
}

@Schema(name = "ServerCounts", description = "Players per backend server, network-wide.")
data class ServerCountsResponse(
    @get:Schema(
        description =
            "One entry per server holding at least one player. A server nobody is on is absent " +
                "rather than zero — the caller knows its own server list and can render the rest " +
                "as empty."
    )
    val servers: List<ServerPlayerCountResponse>,
    @get:Schema(
        description =
            "Everyone online network-wide. Counts players who have not reached a backend server " +
                "yet, so it can exceed the sum of `servers`."
    )
    val total: Int,
) {
    companion object {
        fun from(counts: PresenceService.ServerCounts) =
            ServerCountsResponse(counts.servers.map(ServerPlayerCountResponse::from), counts.total)
    }
}

@Schema(name = "ProxyPlayerCount", description = "One proxy's share of the network.")
data class ProxyPlayerCountResponse(
    val proxyId: String,
    @get:Schema(description = "Absent when the session predates the proxy declaring a region.")
    val region: String?,
    val players: Int,
) {
    companion object {
        fun from(count: PlayerSessionRepository.ProxyPlayerCount) =
            ProxyPlayerCountResponse(count.proxyId, count.region, count.players)
    }
}

@Schema(name = "ProxyCounts", description = "Players per proxy and region, network-wide.")
data class ProxyCountsResponse(
    @get:Schema(description = "One entry per proxy holding at least one player.")
    val proxies: List<ProxyPlayerCountResponse>,
    @get:Schema(
        description =
            "Everyone online network-wide, which equals the sum of `proxies` — every session " +
                "belongs to exactly one proxy, unlike backend servers where a player may be on none."
    )
    val total: Int,
) {
    companion object {
        fun from(counts: PresenceService.ProxyCounts) =
            ProxyCountsResponse(counts.proxies.map(ProxyPlayerCountResponse::from), counts.total)
    }
}

@Schema(name = "PlayerLocale", description = "A player's chosen interface language.")
data class LocaleResponse(
    @get:Schema(
        description =
            "BCP-47 language tag. Null when the player has never chosen one — the caller falls " +
                "back to the locale the client announces.",
        examples = ["de-DE"],
    )
    val locale: String?
)

@Schema(name = "SetLocaleRequest", description = "Store or clear a language preference.")
data class SetLocaleRequest(
    @get:Schema(
        description =
            "The tag to store; null or blank clears the preference. Stored verbatim — the caller " +
                "validates it against the languages it actually ships.",
        examples = ["de-DE"],
    )
    val locale: String? = null
)
