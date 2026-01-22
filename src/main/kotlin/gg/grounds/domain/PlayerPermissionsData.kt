package gg.grounds.domain

import java.util.UUID

data class PlayerPermissionsData(
    val playerId: UUID,
    val groupNames: Set<String>,
    val directPermissions: Set<String>,
    val effectivePermissions: Set<String>,
)
