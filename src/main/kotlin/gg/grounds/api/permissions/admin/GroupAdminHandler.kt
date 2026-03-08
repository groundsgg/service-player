package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsProtoMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.api.permissions.events.PermissionsChangeService
import gg.grounds.domain.permissions.ApplyOutcome
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
import gg.grounds.persistence.permissions.PermissionsRepositoryException
import io.grpc.Status
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.jboss.logging.Logger

@ApplicationScoped
class GroupAdminHandler
@Inject
constructor(
    private val groupRepository: GroupRepository,
    private val permissionsChangeService: PermissionsChangeService,
) {
    fun createGroup(request: CreateGroupRequest): CreateGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Create group request rejected (reason=empty_group_name)")
            throw Status.INVALID_ARGUMENT.withDescription("group_name must be provided")
                .asRuntimeException()
        }

        val outcome = groupRepository.createGroup(groupName)
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to create permission group")
                .asRuntimeException()
        }
        LOG.infof(
            "Create permission group completed (groupName=%s, outcome=%s)",
            groupName,
            outcome,
        )
        return CreateGroupReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun deleteGroup(request: DeleteGroupRequest): DeleteGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Delete group request rejected (reason=empty_group_name)")
            throw Status.INVALID_ARGUMENT.withDescription("group_name must be provided")
                .asRuntimeException()
        }

        val outcome = groupRepository.deleteGroup(groupName)
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to delete permission group")
                .asRuntimeException()
        }
        LOG.infof(
            "Delete permission group completed (groupName=%s, outcome=%s)",
            groupName,
            outcome,
        )
        return DeleteGroupReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun getGroup(request: GetGroupRequest): GetGroupReply {
        val groupName = request.groupName?.trim().orEmpty()
        if (groupName.isEmpty()) {
            LOG.warnf("Get group request rejected (reason=empty_group_name)")
            throw Status.INVALID_ARGUMENT.withDescription("group_name must be provided")
                .asRuntimeException()
        }

        val group =
            try {
                groupRepository.getGroup(groupName, includePermissions = true)
            } catch (error: PermissionsRepositoryException) {
                throw Status.INTERNAL.withDescription("Failed to load permission group")
                    .withCause(error)
                    .asRuntimeException()
            }
                ?: run {
                    LOG.debugf(
                        "Fetch permission group completed (groupName=%s, result=not_found)",
                        groupName,
                    )
                    throw Status.NOT_FOUND.withDescription("permission group not found")
                        .asRuntimeException()
                }
        LOG.debugf("Fetch permission group completed (groupName=%s, result=found)", groupName)
        return GetGroupReply.newBuilder().setGroup(PermissionsProtoMapper.toProto(group)).build()
    }

    fun listGroups(request: ListGroupsRequest): ListGroupsReply {
        val groups =
            try {
                groupRepository.listGroups(request.includePermissions)
            } catch (error: PermissionsRepositoryException) {
                throw Status.INTERNAL.withDescription("Failed to list permission groups")
                    .withCause(error)
                    .asRuntimeException()
            }
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
        val permissionGrants =
            PermissionsRequestParser.sanitizeGroupPermissionGrants(request.permissionGrantsList)
        if (groupName.isEmpty() || permissionGrants.isEmpty()) {
            LOG.warnf(
                "Add group permissions request rejected (groupName=%s, permissionGrantsCount=%d, reason=invalid_input)",
                groupName,
                permissionGrants.size,
            )
            throw Status.INVALID_ARGUMENT.withDescription(
                    "group_name and permission_grants must be provided"
                )
                .asRuntimeException()
        }
        val now = Instant.now()
        if (
            permissionGrants.any { grant ->
                PermissionsRequestParser.hasPastExpiry(grant.expiresAt, now)
            }
        ) {
            LOG.warnf(
                "Add group permissions request rejected (groupName=%s, reason=expired_grant)",
                groupName,
            )
            throw Status.INVALID_ARGUMENT.withDescription("expires_at must be in the future")
                .asRuntimeException()
        }

        val outcome =
            permissionsChangeService.emitGroupPermissionsDeltaIfChanged(
                groupName,
                "group_permission_add",
            ) {
                groupRepository.addGroupPermissions(groupName, permissionGrants)
            }
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to add group permissions")
                .asRuntimeException()
        }
        LOG.infof(
            "Add group permissions completed (groupName=%s, permissionGrantsCount=%d, outcome=%s)",
            groupName,
            permissionGrants.size,
            outcome,
        )
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
            throw Status.INVALID_ARGUMENT.withDescription(
                    "group_name and permissions must be provided"
                )
                .asRuntimeException()
        }

        val outcome =
            permissionsChangeService.emitGroupPermissionsDeltaIfChanged(
                groupName,
                "group_permission_remove",
            ) {
                groupRepository.removeGroupPermissions(groupName, permissions)
            }
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to remove group permissions")
                .asRuntimeException()
        }
        LOG.infof(
            "Remove group permissions completed (groupName=%s, permissionsCount=%d, outcome=%s)",
            groupName,
            permissions.size,
            outcome,
        )
        return RemoveGroupPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(GroupAdminHandler::class.java)
    }
}
