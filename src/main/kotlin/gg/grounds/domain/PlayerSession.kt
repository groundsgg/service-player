package gg.grounds.domain

import java.time.Instant
import java.util.UUID

data class PlayerSession(
    val playerId: UUID,
    val connectedAt: Instant,
)
