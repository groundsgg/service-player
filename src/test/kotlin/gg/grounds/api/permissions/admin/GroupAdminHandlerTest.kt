package gg.grounds.api.permissions.admin

import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddGroupPermissionsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.CreateGroupRequest
import gg.grounds.grpc.permissions.RemoveGroupPermissionsRequest
import gg.grounds.persistence.permissions.GroupRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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

        val reply = handler.createGroup(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
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

        val reply = handler.addGroupPermissions(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
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
