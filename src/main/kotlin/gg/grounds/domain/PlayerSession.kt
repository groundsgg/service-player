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
)
