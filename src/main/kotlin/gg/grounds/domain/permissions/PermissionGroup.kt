package gg.grounds.domain.permissions

import java.time.Instant

data class PermissionGroup(val name: String, val permissionGrants: Set<GroupPermissionGrant>)

data class GroupPermissionGrant(val permission: String, val expiresAt: Instant?)
