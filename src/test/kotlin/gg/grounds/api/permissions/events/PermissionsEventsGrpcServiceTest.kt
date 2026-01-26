package gg.grounds.api.permissions.events

import gg.grounds.grpc.permissions.SubscribePermissionsChangesRequest
import gg.grounds.persistence.PlayerSessionRepository
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PermissionsEventsGrpcServiceTest {
    @Test
    fun subscribeSkipsRefreshWhenNoLastEventId() {
        val publisher = PermissionsEventsPublisher()
        val emitter = mock<PermissionsChangeEmitter>()
        val sessions = mock<PlayerSessionRepository>()
        val service = PermissionsEventsGrpcService(publisher, emitter, sessions)
        val request =
            SubscribePermissionsChangesRequest.newBuilder()
                .setServerId("server-1")
                .setLastEventId("")
                .build()

        service.subscribePermissionsChanges(request)

        verifyNoInteractions(emitter, sessions)
    }

    @Test
    fun subscribeEmitsRefreshWhenLastEventIdPresent() {
        val publisher = PermissionsEventsPublisher()
        val emitter = mock<PermissionsChangeEmitter>()
        val sessions = mock<PlayerSessionRepository>()
        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID()
        whenever(sessions.listActivePlayers()).thenReturn(setOf(playerA, playerB))
        val service = PermissionsEventsGrpcService(publisher, emitter, sessions)
        val request =
            SubscribePermissionsChangesRequest.newBuilder()
                .setServerId("server-2")
                .setLastEventId("event-1")
                .build()

        service.subscribePermissionsChanges(request)

        verify(sessions).listActivePlayers()
        verify(emitter).emitRefresh(eq(playerA), eq("subscription_resume"))
        verify(emitter).emitRefresh(eq(playerB), eq("subscription_resume"))
    }
}
