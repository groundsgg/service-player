package gg.grounds.api.permissions

import gg.grounds.api.permissions.admin.GroupAdminHandler
import gg.grounds.api.permissions.admin.PlayerGroupsAdminHandler
import gg.grounds.api.permissions.admin.PlayerPermissionsAdminHandler
import gg.grounds.grpc.permissions.AddGroupPermissionsReply
import gg.grounds.grpc.permissions.AddGroupPermissionsRequest
import gg.grounds.grpc.permissions.AddPlayerGroupsReply
import gg.grounds.grpc.permissions.AddPlayerGroupsRequest
import gg.grounds.grpc.permissions.AddPlayerPermissionsReply
import gg.grounds.grpc.permissions.AddPlayerPermissionsRequest
import gg.grounds.grpc.permissions.CreateGroupReply
import gg.grounds.grpc.permissions.CreateGroupRequest
import gg.grounds.grpc.permissions.DeleteGroupReply
import gg.grounds.grpc.permissions.DeleteGroupRequest
import gg.grounds.grpc.permissions.GetGroupReply
import gg.grounds.grpc.permissions.GetGroupRequest
import gg.grounds.grpc.permissions.ListGroupsReply
import gg.grounds.grpc.permissions.ListGroupsRequest
import gg.grounds.grpc.permissions.PermissionsAdminService
import gg.grounds.grpc.permissions.RemoveGroupPermissionsReply
import gg.grounds.grpc.permissions.RemoveGroupPermissionsRequest
import gg.grounds.grpc.permissions.RemovePlayerGroupsReply
import gg.grounds.grpc.permissions.RemovePlayerGroupsRequest
import gg.grounds.grpc.permissions.RemovePlayerPermissionsReply
import gg.grounds.grpc.permissions.RemovePlayerPermissionsRequest
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject

@GrpcService
@Blocking
class PermissionsAdminGrpcService
@Inject
constructor(
    private val groupAdminHandler: GroupAdminHandler,
    private val playerPermissionsAdminHandler: PlayerPermissionsAdminHandler,
    private val playerGroupsAdminHandler: PlayerGroupsAdminHandler,
) : PermissionsAdminService {
    override fun createGroup(request: CreateGroupRequest): Uni<CreateGroupReply> {
        return Uni.createFrom().item { groupAdminHandler.createGroup(request) }
    }

    override fun deleteGroup(request: DeleteGroupRequest): Uni<DeleteGroupReply> {
        return Uni.createFrom().item { groupAdminHandler.deleteGroup(request) }
    }

    override fun getGroup(request: GetGroupRequest): Uni<GetGroupReply> {
        return Uni.createFrom().item { groupAdminHandler.getGroup(request) }
    }

    override fun listGroups(request: ListGroupsRequest): Uni<ListGroupsReply> {
        return Uni.createFrom().item { groupAdminHandler.listGroups(request) }
    }

    override fun addGroupPermissions(
        request: AddGroupPermissionsRequest
    ): Uni<AddGroupPermissionsReply> {
        return Uni.createFrom().item { groupAdminHandler.addGroupPermissions(request) }
    }

    override fun removeGroupPermissions(
        request: RemoveGroupPermissionsRequest
    ): Uni<RemoveGroupPermissionsReply> {
        return Uni.createFrom().item { groupAdminHandler.removeGroupPermissions(request) }
    }

    override fun addPlayerPermissions(
        request: AddPlayerPermissionsRequest
    ): Uni<AddPlayerPermissionsReply> {
        return Uni.createFrom().item { playerPermissionsAdminHandler.addPlayerPermissions(request) }
    }

    override fun removePlayerPermissions(
        request: RemovePlayerPermissionsRequest
    ): Uni<RemovePlayerPermissionsReply> {
        return Uni.createFrom().item {
            playerPermissionsAdminHandler.removePlayerPermissions(request)
        }
    }

    override fun addPlayerGroups(request: AddPlayerGroupsRequest): Uni<AddPlayerGroupsReply> {
        return Uni.createFrom().item { playerGroupsAdminHandler.addPlayerGroups(request) }
    }

    override fun removePlayerGroups(
        request: RemovePlayerGroupsRequest
    ): Uni<RemovePlayerGroupsReply> {
        return Uni.createFrom().item { playerGroupsAdminHandler.removePlayerGroups(request) }
    }
}
