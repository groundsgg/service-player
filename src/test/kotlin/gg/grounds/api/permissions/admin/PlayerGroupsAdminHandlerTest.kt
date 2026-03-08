package gg.grounds.api.permissions.admin

import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerGroupsRequest
import gg.grounds.grpc.permissions.ApplyResult
import gg.grounds.grpc.permissions.RemovePlayerGroupsRequest
import gg.grounds.persistence.permissions.PlayerGroupRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PlayerGroupsAdminHandlerTest {
    @Test
    fun addPlayerGroupsRejectsInvalidPlayerId() {
        val repository = mock<PlayerGroupRepository>()
        val handler = PlayerGroupsAdminHandler(repository)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId("not-a-uuid")
                .addGroupNames("vip")
                .build()

        val reply = handler.addPlayerGroups(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
        verifyNoInteractions(repository)
    }

    @Test
    fun addPlayerGroupsReturnsOutcome() {
        val repository = mock<PlayerGroupRepository>()
        val handler = PlayerGroupsAdminHandler(repository)
        val playerId = UUID.randomUUID()
        whenever(repository.addPlayerGroups(playerId, setOf("vip")))
            .thenReturn(ApplyOutcome.UPDATED)
        val request =
            AddPlayerGroupsRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .addGroupNames("vip")
                .build()

        val reply = handler.addPlayerGroups(request)

        assertEquals(ApplyResult.UPDATED, reply.applyResult)
        verify(repository).addPlayerGroups(playerId, setOf("vip"))
    }

    @Test
    fun removePlayerGroupsRejectsEmptyGroupNames() {
        val repository = mock<PlayerGroupRepository>()
        val handler = PlayerGroupsAdminHandler(repository)
        val request =
            RemovePlayerGroupsRequest.newBuilder().setPlayerId(UUID.randomUUID().toString()).build()

        val reply = handler.removePlayerGroups(request)

        assertEquals(ApplyResult.APPLY_RESULT_UNSPECIFIED, reply.applyResult)
        verifyNoInteractions(repository)
    }
}
