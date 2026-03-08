package gg.grounds.api.permissions.admin

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.domain.permissions.GroupPermissionGrant
import gg.grounds.grpc.permissions.AddGroupPermissionsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.CreateGroupRequest
import gg.grounds.grpc.permissions.PermissionGrant as PermissionGrantProto
import gg.grounds.grpc.permissions.RemoveGroupPermissionsRequest
import gg.grounds.persistence.permissions.GroupRepository
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class GroupAdminHandlerTest {
    @Test
    fun createGroupRejectsEmptyName() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        val request = CreateGroupRequest.newBuilder().setGroupName(" ").build()

        val exception = assertThrows<StatusRuntimeException> { handler.createGroup(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("group_name must be provided", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun createGroupReturnsOutcome() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        whenever(repository.createGroup("admins")).thenReturn(ApplyOutcome.CREATED)
        val request = CreateGroupRequest.newBuilder().setGroupName("admins").build()

        val reply = handler.createGroup(request)

        assertEquals(ApplyResult.CREATED, reply.applyResult)
        verify(repository).createGroup("admins")
    }

    @Test
    fun addGroupPermissionsRejectsEmptyPermissions() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        val request = AddGroupPermissionsRequest.newBuilder().setGroupName("admins").build()

        val exception =
            assertThrows<StatusRuntimeException> { handler.addGroupPermissions(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("group_name and permission_grants must be provided", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun addGroupPermissionsReturnsOutcome() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        whenever(
                repository.addGroupPermissions(
                    "admins",
                    setOf(GroupPermissionGrant("permission.read", null)),
                )
            )
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddGroupPermissionsRequest.newBuilder()
                .setGroupName("admins")
                .addPermissionGrants(
                    PermissionGrantProto.newBuilder().setPermission("permission.read").build()
                )
                .build()

        val reply = handler.addGroupPermissions(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository)
            .addGroupPermissions("admins", setOf(GroupPermissionGrant("permission.read", null)))
    }

    @Test
    fun addGroupPermissionsRejectsPastExpiry() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        val expiresAt = Instant.now().minusSeconds(10)
        val request =
            AddGroupPermissionsRequest.newBuilder()
                .setGroupName("admins")
                .addPermissionGrants(
                    PermissionGrantProto.newBuilder()
                        .setPermission("permission.read")
                        .setExpiresAt(
                            com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(expiresAt.epochSecond)
                                .setNanos(expiresAt.nano)
                                .build()
                        )
                        .build()
                )
                .build()

        val exception =
            assertThrows<StatusRuntimeException> { handler.addGroupPermissions(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("expires_at must be in the future", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun removeGroupPermissionsReturnsOutcome() {
        val repository = mock<GroupRepository>()
        val handler = GroupAdminHandler(repository)
        whenever(repository.removeGroupPermissions("admins", setOf("permission.read")))
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            RemoveGroupPermissionsRequest.newBuilder()
                .setGroupName("admins")
                .addPermissions("permission.read")
                .build()

        val reply = handler.removeGroupPermissions(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository).removeGroupPermissions("admins", setOf("permission.read"))
    }
}
