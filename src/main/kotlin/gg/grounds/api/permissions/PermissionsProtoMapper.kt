package gg.grounds.api.permissions

import gg.grounds.domain.PermissionGroup
import gg.grounds.domain.PlayerPermissionsData
import gg.grounds.grpc.permissions.Group
import gg.grounds.grpc.permissions.PlayerPermissions

object PermissionsProtoMapper {
    fun toProto(data: PlayerPermissionsData): PlayerPermissions {
        return PlayerPermissions.newBuilder()
            .setPlayerId(data.playerId.toString())
            .addAllGroupNames(data.groupNames)
            .addAllDirectPermissions(data.directPermissions)
            .addAllEffectivePermissions(data.effectivePermissions)
            .build()
    }

    fun toProto(group: PermissionGroup): Group {
        return Group.newBuilder()
            .setGroupName(group.name)
            .addAllPermissions(group.permissions)
            .build()
    }
}
