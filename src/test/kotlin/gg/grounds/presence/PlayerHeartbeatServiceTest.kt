package gg.grounds.presence

import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.TouchSessionsResult
import gg.grounds.presence.PlayerHeartbeatService.HeartbeatOutcome
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@QuarkusTest
class PlayerHeartbeatServiceTest {

    @InjectMock lateinit var repository: PlayerSessionRepository

    @Inject lateinit var heartbeatService: PlayerHeartbeatService

    @BeforeEach
    fun resetMocks() {
        reset(repository)
    }

    @Test
    fun heartbeatBatchRejectsInvalidPlayerIds() {
        val outcome =
            heartbeatService.handleHeartbeatBatch(listOf("bad-id", UUID.randomUUID().toString()))

        assertEquals(HeartbeatOutcome.Rejected(HeartbeatOutcome.Reason.INVALID_PLAYER_IDS), outcome)
        verifyNoInteractions(repository)
    }

    @Test
    fun heartbeatBatchRejectsEmptyPlayerIds() {
        val outcome = heartbeatService.handleHeartbeatBatch(emptyList())

        assertEquals(HeartbeatOutcome.Rejected(HeartbeatOutcome.Reason.EMPTY), outcome)
        verifyNoInteractions(repository)
    }

    @Test
    fun heartbeatBatchUpdatesSessions() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(first, second)), any()))
            .thenReturn(TouchSessionsResult.Updated(2))

        val outcome =
            heartbeatService.handleHeartbeatBatch(listOf(first.toString(), second.toString()))

        assertEquals(HeartbeatOutcome.Accepted(updated = 2, missing = 0), outcome)
        verify(repository).touchSessions(eq(listOf(first, second)), any())
    }

    @Test
    fun heartbeatBatchReportsMissingSessions() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(first, second)), any()))
            .thenReturn(TouchSessionsResult.Updated(1))

        val outcome =
            heartbeatService.handleHeartbeatBatch(listOf(first.toString(), second.toString()))

        assertEquals(HeartbeatOutcome.Accepted(updated = 1, missing = 1), outcome)
        verify(repository).touchSessions(eq(listOf(first, second)), any())
    }

    @Test
    fun heartbeatBatchReturnsErrorWhenSessionUpdateFails() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(first, second)), any()))
            .thenReturn(TouchSessionsResult.Error)

        val outcome =
            heartbeatService.handleHeartbeatBatch(listOf(first.toString(), second.toString()))

        assertEquals(
            HeartbeatOutcome.Failed(missing = 2, message = "unable to update player sessions"),
            outcome,
        )
        verify(repository).touchSessions(eq(listOf(first, second)), any())
    }
}
