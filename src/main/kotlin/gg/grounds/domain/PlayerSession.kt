package gg.grounds.domain

import java.time.Instant
import java.util.UUID

data class PlayerSession(
    val playerId: UUID,
    val connectedAt: Instant,
    val lastSeenAt: Instant,
    val playerName: String? = null,
    val proxyId: String? = null,
    val serverName: String? = null,
    /**
     * Where the proxy is. Null when the proxy declares no region, and null for every session that
     * predates a rollout — "unknown" is a normal answer here, not a fault.
     */
    val region: String? = null,
)
