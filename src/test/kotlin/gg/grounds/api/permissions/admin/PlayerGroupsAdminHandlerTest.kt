package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.events.PermissionsChangeService
import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerGroupsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.PlayerGroupMembership as PlayerGroupMembershipProto
import gg.grounds.grpc.permissions.RemovePlayerGroupsRequest
import gg.grounds.persistence.permissions.PlayerGroupRepository
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PlayerGroupsAdminHandlerTest {
    @Test
    fun addPlayerGroupsRejectsInvalidPlayerId() {
        val repository = mock<PlayerGroupRepository>()
        val changeService = mock<PermissionsChangeService>()
        val handler = PlayerGroupsAdminHandler(repository, changeService)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId("not-a-uuid")
                .addGroupMemberships(
                    PlayerGroupMembershipProto.newBuilder().setGroupName("vip").build()
                )
                .build()

        val exception = assertThrows<StatusRuntimeException> { handler.addPlayerGroups(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("player_id must be a UUID", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun addPlayerGroupsReturnsOutcome() {
        val repository = mock<PlayerGroupRepository>()
        val changeService = mock<PermissionsChangeService>()
        val handler = PlayerGroupsAdminHandler(repository, changeService)
        val playerId = UUID.randomUUID()
        whenever(
                changeService.emitPlayerDeltaIfChanged(eq(playerId), eq("player_group_add"), any())
            )
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addGroupMemberships(
                    PlayerGroupMembershipProto.newBuilder().setGroupName("vip").build()
                )
                .build()

        val reply = handler.addPlayerGroups(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(changeService).emitPlayerDeltaIfChanged(eq(playerId), eq("player_group_add"), any())
    }

    @Test
    fun addPlayerGroupsAcceptsExpiry() {
        val repository = mock<PlayerGroupRepository>()
        val changeService = mock<PermissionsChangeService>()
        val handler = PlayerGroupsAdminHandler(repository, changeService)
        val playerId = UUID.randomUUID()
        val expiresAt = Instant.now().plusSeconds(60)
        whenever(
                changeService.emitPlayerDeltaIfChanged(eq(playerId), eq("player_group_add"), any())
            )
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addGroupMemberships(
                    PlayerGroupMembershipProto.newBuilder()
                        .setGroupName("vip")
                        .setExpiresAt(
                            com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(expiresAt.epochSecond)
                                .setNanos(expiresAt.nano)
                                .build()
                        )
                        .build()
                )
                .build()

        val reply = handler.addPlayerGroups(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(changeService).emitPlayerDeltaIfChanged(eq(playerId), eq("player_group_add"), any())
    }

    @Test
    fun addPlayerGroupsRejectsPastExpiry() {
        val repository = mock<PlayerGroupRepository>()
        val changeService = mock<PermissionsChangeService>()
        val handler = PlayerGroupsAdminHandler(repository, changeService)
        val playerId = UUID.randomUUID()
        val expiresAt = Instant.now().minusSeconds(10)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addGroupMemberships(
                    PlayerGroupMembershipProto.newBuilder()
                        .setGroupName("vip")
                        .setExpiresAt(
                            com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(expiresAt.epochSecond)
                                .setNanos(expiresAt.nano)
                                .build()
                        )
                        .build()
                )
                .build()

        val exception = assertThrows<StatusRuntimeException> { handler.addPlayerGroups(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("expires_at must be in the future", status.description)
        verifyNoInteractions(repository)
    }

    @Test
    fun removePlayerGroupsRejectsEmptyGroupNames() {
        val repository = mock<PlayerGroupRepository>()
        val changeService = mock<PermissionsChangeService>()
        val handler = PlayerGroupsAdminHandler(repository, changeService)
        val request =
            RemovePlayerGroupsRequest.newBuilder().setPlayerId(UUID.randomUUID().toString()).build()

        val exception = assertThrows<StatusRuntimeException> { handler.removePlayerGroups(request) }

        val status = Status.fromThrowable(exception)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("group_names must be provided", status.description)
        verifyNoInteractions(repository)
    }
}
