package gg.grounds.api.permissions

import com.google.protobuf.Timestamp
import gg.grounds.domain.permissions.GroupPermissionGrant
import gg.grounds.domain.permissions.PermissionGroup
import gg.grounds.domain.permissions.PlayerGroupMembership
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.domain.permissions.PlayerPermissionsData
import gg.grounds.grpc.permissions.Group
import gg.grounds.grpc.permissions.PermissionGrant as PermissionGrantProto
import gg.grounds.grpc.permissions.PlayerGroupMembership as PlayerGroupMembershipProto
import gg.grounds.grpc.permissions.PlayerPermissions

object PermissionsProtoMapper {
    fun toProto(data: PlayerPermissionsData): PlayerPermissions {
        return PlayerPermissions.newBuilder()
            .setPlayerId(data.playerId.toString())
            .addAllGroupMemberships(data.groupMemberships.map { toProto(it) })
            .addAllDirectPermissionGrants(data.directPermissionGrants.map { toProto(it) })
            .addAllEffectivePermissions(data.effectivePermissions)
            .build()
    }

    fun toProto(group: PermissionGroup): Group {
        return Group.newBuilder()
            .setGroupName(group.name)
            .addAllPermissionGrants(group.permissionGrants.map { toProto(it) })
            .build()
    }

    private fun toProto(membership: PlayerGroupMembership): PlayerGroupMembershipProto {
        val builder = PlayerGroupMembershipProto.newBuilder().setGroupName(membership.groupName)
        membership.expiresAt?.let { builder.setExpiresAt(toTimestamp(it)) }
        return builder.build()
    }

    private fun toProto(grant: PlayerPermissionGrant): PermissionGrantProto {
        val builder = PermissionGrantProto.newBuilder().setPermission(grant.permission)
        grant.expiresAt?.let { builder.setExpiresAt(toTimestamp(it)) }
        return builder.build()
    }

    private fun toProto(grant: GroupPermissionGrant): PermissionGrantProto {
        val builder = PermissionGrantProto.newBuilder().setPermission(grant.permission)
        grant.expiresAt?.let { builder.setExpiresAt(toTimestamp(it)) }
        return builder.build()
    }

    private fun toTimestamp(instant: java.time.Instant): Timestamp {
        return Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
    }
}
