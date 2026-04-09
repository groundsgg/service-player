package gg.grounds.api.permissions.events

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.persistence.permissions.PermissionsQueryRepository
import gg.grounds.persistence.permissions.PlayerGroupRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PermissionsChangeServiceTest {
    @Test
    fun emitGroupPermissionsDeltaIfChangedEmitsRefreshForEachPlayer() {
        val groupRepository = mock<PlayerGroupRepository>()
        val queryRepository = mock<PermissionsQueryRepository>()
        val emitter = mock<PermissionsChangeEmitter>()
        val changeService = PermissionsChangeService(groupRepository, queryRepository, emitter)
        val playerId = UUID.randomUUID()
        whenever(groupRepository.listActivePlayersForGroup("admins")).thenReturn(setOf(playerId))

        val outcome =
            changeService.emitGroupPermissionsDeltaIfChanged("admins", "group_permission_add") {
                ApplyOutcome.UPDATED
            }

        verify(emitter).emitRefresh(playerId, "group_permission_add")
        verifyNoInteractions(queryRepository)
        assertEquals(ApplyOutcome.UPDATED, outcome)
    }

    @Test
    fun emitGroupPermissionsDeltaIfChangedSkipsRefreshOnNoChange() {
        val groupRepository = mock<PlayerGroupRepository>()
        val queryRepository = mock<PermissionsQueryRepository>()
        val emitter = mock<PermissionsChangeEmitter>()
        val changeService = PermissionsChangeService(groupRepository, queryRepository, emitter)
        val playerId = UUID.randomUUID()
        whenever(groupRepository.listActivePlayersForGroup("admins")).thenReturn(setOf(playerId))

        val outcome =
            changeService.emitGroupPermissionsDeltaIfChanged("admins", "group_permission_add") {
                ApplyOutcome.NO_CHANGE
            }

        verify(emitter, never()).emitRefresh(any(), any())
        verifyNoInteractions(queryRepository)
        assertEquals(ApplyOutcome.NO_CHANGE, outcome)
    }
}
