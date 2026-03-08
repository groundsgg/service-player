package gg.grounds.api.permissions.admin

import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerPermissionsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.RemovePlayerPermissionsRequest
import gg.grounds.persistence.permissions.PlayerPermissionRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PlayerPermissionsAdminHandlerTest {
    @Test
    fun addPlayerPermissionsRejectsInvalidPlayerId() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val request =
            AddPlayerPermissionsRequest.newBuilder()
                .setPlayerId("not-a-uuid")
                .addPermissions("permission.read")
                .build()

        val reply = handler.addPlayerPermissions(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
        verifyNoInteractions(repository)
    }

    @Test
    fun addPlayerPermissionsReturnsOutcome() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val playerId = UUID.randomUUID()
        whenever(repository.addPlayerPermissions(playerId, setOf("permission.read")))
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerPermissionsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addPermissions("permission.read")
                .build()

        val reply = handler.addPlayerPermissions(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository).addPlayerPermissions(playerId, setOf("permission.read"))
    }

    @Test
    fun removePlayerPermissionsRejectsEmptyPermissions() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val request =
            RemovePlayerPermissionsRequest.newBuilder()
                .setPlayerId(UUID.randomUUID().toString())
                .build()

        val reply = handler.removePlayerPermissions(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
        verifyNoInteractions(repository)
    }
}
