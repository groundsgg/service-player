package gg.grounds.api.permissions

import gg.grounds.domain.permissions.PlayerGroupMembership
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.domain.permissions.PlayerPermissionsData
import gg.grounds.grpc.permissions.CheckPlayerPermissionRequest
import gg.grounds.grpc.permissions.GetPlayerPermissionsRequest
import gg.grounds.persistence.permissions.PermissionsQueryRepository
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

class PermissionsPluginGrpcServiceTest {
    @Test
    fun getPlayerPermissionsRejectsInvalidPlayerId() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val request = GetPlayerPermissionsRequest.newBuilder().setPlayerId("not-a-uuid").build()

        val exception =
            assertThrows<StatusRuntimeException> {
                service.getPlayerPermissions(request).await().indefinitely()
            }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("player_id must be a UUID", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun getPlayerPermissionsReturnsPermissions() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val playerId = UUID.randomUUID()
        val expiresAt = Instant.ofEpochSecond(1_700_000_000)
        val data =
            PlayerPermissionsData(
                playerId,
                setOf(PlayerGroupMembership("group-a", expiresAt)),
                setOf(PlayerPermissionGrant("permission.read", expiresAt)),
                setOf("permission.read", "permission.write"),
            )
        whenever(repository.getPlayerPermissions(playerId, true, false, true)).thenReturn(data)
        val request =
            GetPlayerPermissionsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .setIncludeEffectivePermissions(true)
                .setIncludeDirectPermissions(false)
                .setIncludeGroups(true)
                .build()

        val reply = service.getPlayerPermissions(request).await().indefinitely()

        assertEquals(playerId.toString(), reply.player.playerId)
        assertEquals(
            setOf("group-a"),
            reply.player.groupMembershipsList.map { it.groupName }.toSet(),
        )
        assertEquals(
            expiresAt.epochSecond,
            reply.player.groupMembershipsList.first().expiresAt.seconds,
        )
        assertEquals(
            setOf("permission.read"),
            reply.player.directPermissionGrantsList.map { it.permission }.toSet(),
        )
        assertEquals(
            expiresAt.epochSecond,
            reply.player.directPermissionGrantsList.first().expiresAt.seconds,
        )
        assertEquals(
            setOf("permission.read", "permission.write"),
            reply.player.effectivePermissionsList.toSet(),
        )
        verify(repository).getPlayerPermissions(playerId, true, false, true)
    }

    @Test
    fun getPlayerPermissionsFailsWhenRepositoryErrors() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val playerId = UUID.randomUUID()
        whenever(repository.getPlayerPermissions(playerId, false, false, false)).thenReturn(null)
        val request =
            GetPlayerPermissionsRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val exception =
            assertThrows<StatusRuntimeException> {
                service.getPlayerPermissions(request).await().indefinitely()
            }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INTERNAL, status.code)
        assertEquals("Failed to load player permissions", status.description)
        verify(repository).getPlayerPermissions(playerId, false, false, false)
    }

    @Test
    fun checkPlayerPermissionRejectsEmptyPermission() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val request =
            CheckPlayerPermissionRequest.newBuilder()
                .setPlayerId(UUID.randomUUID().toString())
                .setPermission("  ")
                .build()

        val exception =
            assertThrows<StatusRuntimeException> {
                service.checkPlayerPermission(request).await().indefinitely()
            }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("permission must be provided", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun checkPlayerPermissionReturnsAllowed() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val playerId = UUID.randomUUID()
        whenever(repository.checkPlayerPermission(playerId, "permission.read")).thenReturn(true)
        val request =
            CheckPlayerPermissionRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .setPermission("permission.read")
                .build()

        val reply = service.checkPlayerPermission(request).await().indefinitely()

        assertEquals(true, reply.allowed)
        verify(repository).checkPlayerPermission(playerId, "permission.read")
    }

    @Test
    fun checkPlayerPermissionFailsWhenRepositoryErrors() {
        val repository = mock<PermissionsQueryRepository>()
        val service = PermissionsPluginGrpcService(repository)
        val playerId = UUID.randomUUID()
        whenever(repository.checkPlayerPermission(playerId, "permission.read")).thenReturn(null)
        val request =
            CheckPlayerPermissionRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .setPermission("permission.read")
                .build()

        val exception =
            assertThrows<StatusRuntimeException> {
                service.checkPlayerPermission(request).await().indefinitely()
            }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INTERNAL, status.code)
        assertEquals("Failed to check player permission", status.description)
        verify(repository).checkPlayerPermission(playerId, "permission.read")
    }
}
