package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsProtoMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddGroupPermissionsReply
import gg.grounds.grpc.permissions.AddGroupPermissionsRequest
import gg.grounds.grpc.permissions.CreateGroupReply
import gg.grounds.grpc.permissions.CreateGroupRequest
import gg.grounds.grpc.permissions.DeleteGroupReply
import gg.grounds.grpc.permissions.DeleteGroupRequest
import gg.grounds.grpc.permissions.GetGroupReply
import gg.grounds.grpc.permissions.GetGroupRequest
import gg.grounds.grpc.permissions.ListGroupsReply
import gg.grounds.grpc.permissions.ListGroupsRequest
import gg.grounds.grpc.permissions.RemoveGroupPermissionsReply
import gg.grounds.grpc.permissions.RemoveGroupPermissionsRequest
import gg.grounds.persistence.permissions.GroupRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class GroupAdminHandler @Inject constructor(private val groupRepository: GroupRepository) {
    fun createGroup(request: CreateGroupRequest): CreateGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Create group request rejected (reason=empty_group_name)")
            return CreateGroupReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = groupRepository.createGroup(groupName)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Create permission group completed (groupName=%s, outcome=%s)",
                groupName,
                outcome,
            )
        }
        return CreateGroupReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun deleteGroup(request: DeleteGroupRequest): DeleteGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Delete group request rejected (reason=empty_group_name)")
            return DeleteGroupReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = groupRepository.deleteGroup(groupName)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Delete permission group completed (groupName=%s, outcome=%s)",
                groupName,
                outcome,
            )
        }
        return DeleteGroupReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun getGroup(request: GetGroupRequest): GetGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Get group request rejected (reason=empty_group_name)")
            return GetGroupReply.getDefaultInstance()
        }

        val group =
            groupRepository.getGroup(groupName, includePermissions = true)
                ?: run {
                    LOG.debugf(
                        "Fetch permission group completed (groupName=%s, result=not_found)",
                        groupName,
                    )
                    return GetGroupReply.getDefaultInstance()
                }
        LOG.debugf("Fetch permission group completed (groupName=%s, result=found)", groupName)
        return GetGroupReply.newBuilder().setGroup(PermissionsProtoMapper.toProto(group)).build()
    }

    fun listGroups(request: ListGroupsRequest): ListGroupsReply {
        val groups = groupRepository.listGroups(request.includePermissions)
        LOG.debugf(
            "Listed permission groups successfully (includePermissions=%s, groupCount=%d)",
            request.includePermissions,
            groups.size,
        )
        val builder = ListGroupsReply.newBuilder()
        groups.forEach { group -> builder.addGroups(PermissionsProtoMapper.toProto(group)) }
        return builder.build()
    }

    fun addGroupPermissions(request: AddGroupPermissionsRequest): AddGroupPermissionsReply {
        val groupName = request.groupName?.trim().orEmpty()
        val permissions = PermissionsRequestParser.sanitize(request.permissionsList)
        if (groupName.isEmpty() || permissions.isEmpty()) {
            LOG.warnf(
                "Add group permissions request rejected (groupName=%s, permissionsCount=%d, reason=invalid_input)",
                groupName,
                permissions.size,
            )
            return AddGroupPermissionsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = groupRepository.addGroupPermissions(groupName, permissions)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Add group permissions completed (groupName=%s, permissionsCount=%d, outcome=%s)",
                groupName,
                permissions.size,
                outcome,
            )
        }
        return AddGroupPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun removeGroupPermissions(
        request: RemoveGroupPermissionsRequest
    ): RemoveGroupPermissionsReply {
        val groupName = request.groupName?.trim().orEmpty()
        val permissions = PermissionsRequestParser.sanitize(request.permissionsList)
        if (groupName.isEmpty() || permissions.isEmpty()) {
            LOG.warnf(
                "Remove group permissions request rejected (groupName=%s, permissionsCount=%d, reason=invalid_input)",
                groupName,
                permissions.size,
            )
            return RemoveGroupPermissionsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = groupRepository.removeGroupPermissions(groupName, permissions)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Remove group permissions completed (groupName=%s, permissionsCount=%d, outcome=%s)",
                groupName,
                permissions.size,
                outcome,
            )
        }
        return RemoveGroupPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(GroupAdminHandler::class.java)
    }
}
