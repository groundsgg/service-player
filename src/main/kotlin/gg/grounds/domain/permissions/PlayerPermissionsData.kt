package gg.grounds.domain.permissions

import java.time.Instant
import java.util.UUID

data class PlayerPermissionsData(
    val playerId: UUID,
    val groupMemberships: Set<PlayerGroupMembership>,
    val directPermissionGrants: Set<PlayerPermissionGrant>,
    val effectivePermissions: Set<String>,
)

data class PlayerGroupMembership(val groupName: String, val expiresAt: Instant?)

data class PlayerPermissionGrant(val permission: String, val expiresAt: Instant?)
