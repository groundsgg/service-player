package gg.grounds.api.permissions

import com.google.protobuf.Timestamp
import gg.grounds.domain.permissions.GroupPermissionGrant
import gg.grounds.domain.permissions.PlayerGroupMembership
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.grpc.permissions.PermissionGrant as PermissionGrantProto
import gg.grounds.grpc.permissions.PlayerGroupMembership as PlayerGroupMembershipProto
import java.time.Instant
import java.util.UUID

object PermissionsRequestParser {
    fun parsePlayerId(value: String?): UUID? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    fun sanitize(values: List<String>): Set<String> {
        return values.mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }.toSet()
    }

    fun sanitizeGroupMemberships(
        values: List<PlayerGroupMembershipProto>
    ): Set<PlayerGroupMembership> {
        val memberships = linkedMapOf<String, PlayerGroupMembership>()
        values.forEach { membership ->
            val groupName = membership.groupName?.trim().orEmpty()
            if (groupName.isEmpty()) {
                return@forEach
            }
            val expiresAt = if (membership.hasExpiresAt()) toInstant(membership.expiresAt) else null
            memberships[groupName] = PlayerGroupMembership(groupName, expiresAt)
        }
        return memberships.values.toSet()
    }

    fun sanitizePermissionGrants(values: List<PermissionGrantProto>): Set<PlayerPermissionGrant> {
        return parsePermissionGrants(values)
            .map { (permission, expiresAt) -> PlayerPermissionGrant(permission, expiresAt) }
            .toSet()
    }

    fun sanitizeGroupPermissionGrants(
        values: List<PermissionGrantProto>
    ): Set<GroupPermissionGrant> {
        return parsePermissionGrants(values)
            .map { (permission, expiresAt) -> GroupPermissionGrant(permission, expiresAt) }
            .toSet()
    }

    private fun parsePermissionGrants(values: List<PermissionGrantProto>): Map<String, Instant?> {
        val grants = linkedMapOf<String, Instant?>()
        values.forEach { grant ->
            val permission = grant.permission?.trim().orEmpty()
            if (permission.isEmpty()) {
                return@forEach
            }
            val expiresAt = if (grant.hasExpiresAt()) toInstant(grant.expiresAt) else null
            grants[permission] = expiresAt
        }
        return grants
    }

    private fun toInstant(timestamp: Timestamp): Instant {
        return Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
    }

    fun hasPastExpiry(expiresAt: Instant?, now: Instant): Boolean {
        return expiresAt != null && !expiresAt.isAfter(now)
    }
}
