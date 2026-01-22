package gg.grounds.api.permissions.admin

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.grpc.permissions.AddPlayerPermissionsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.PermissionGrant as PermissionGrantProto
import gg.grounds.grpc.permissions.RemovePlayerPermissionsRequest
import gg.grounds.persistence.permissions.PlayerPermissionRepository
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
                .addPermissionGrants(
                    PermissionGrantProto.newBuilder().setPermission("permission.read").build()
                )
                .build()

        val exception =
            assertThrows<StatusRuntimeException> { handler.addPlayerPermissions(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("player_id must be a UUID", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun addPlayerPermissionsReturnsOutcome() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val playerId = UUID.randomUUID()
        whenever(
                repository.addPlayerPermissions(
                    playerId,
                    setOf(PlayerPermissionGrant("permission.read", null)),
                )
            )
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerPermissionsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addPermissionGrants(
                    PermissionGrantProto.newBuilder().setPermission("permission.read").build()
                )
                .build()

        val reply = handler.addPlayerPermissions(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository)
            .addPlayerPermissions(playerId, setOf(PlayerPermissionGrant("permission.read", null)))
    }

    @Test
    fun addPlayerPermissionsAcceptsExpiry() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val playerId = UUID.randomUUID()
        val expiresAt = Instant.now().plusSeconds(60)
        whenever(
                repository.addPlayerPermissions(
                    playerId,
                    setOf(PlayerPermissionGrant("permission.read", expiresAt)),
                )
            )
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerPermissionsRequest.newBuilder()
                .setPlayerId(playerId.toString())
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

        val reply = handler.addPlayerPermissions(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository)
            .addPlayerPermissions(
                playerId,
                setOf(PlayerPermissionGrant("permission.read", expiresAt)),
            )
    }

    @Test
    fun addPlayerPermissionsRejectsPastExpiry() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val playerId = UUID.randomUUID()
        val expiresAt = Instant.now().minusSeconds(5)
        val request =
            AddPlayerPermissionsRequest.newBuilder()
                .setPlayerId(playerId.toString())
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
            assertThrows<StatusRuntimeException> { handler.addPlayerPermissions(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("expires_at must be in the future", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun removePlayerPermissionsRejectsEmptyPermissions() {
        val repository = mock<PlayerPermissionRepository>()
        val handler = PlayerPermissionsAdminHandler(repository)
        val request =
            RemovePlayerPermissionsRequest.newBuilder()
                .setPlayerId(UUID.randomUUID().toString())
                .build()

        val exception =
            assertThrows<StatusRuntimeException> { handler.removePlayerPermissions(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("permissions must be provided", status.description)
        verifyNoInteractions(repository)
    }
}
