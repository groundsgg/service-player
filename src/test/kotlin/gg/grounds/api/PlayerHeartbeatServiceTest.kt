package gg.grounds.api

import gg.grounds.grpc.player.PlayerHeartbeatBatchReply
import gg.grounds.grpc.player.PlayerHeartbeatBatchRequest
import gg.grounds.persistence.PlayerSessionRepository
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
        val request =
            PlayerHeartbeatBatchRequest.newBuilder()
                .addPlayerIds("bad-id")
                .addPlayerIds(UUID.randomUUID().toString())
                .build()

        val reply: PlayerHeartbeatBatchReply = heartbeatService.handleHeartbeatBatch(request)

        assertEquals(0, reply.updated)
        assertEquals(0, reply.missing)
        assertEquals("player_ids must be UUIDs", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun heartbeatBatchRejectsEmptyPlayerIds() {
        val request = PlayerHeartbeatBatchRequest.newBuilder().build()

        val reply: PlayerHeartbeatBatchReply = heartbeatService.handleHeartbeatBatch(request)

        assertEquals(0, reply.updated)
        assertEquals(0, reply.missing)
        assertEquals("no player ids provided", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun heartbeatBatchUpdatesSessions() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(first, second)), any())).thenReturn(2)

        val request =
            PlayerHeartbeatBatchRequest.newBuilder()
                .addPlayerIds(first.toString())
                .addPlayerIds(second.toString())
                .build()

        val reply: PlayerHeartbeatBatchReply = heartbeatService.handleHeartbeatBatch(request)

        assertEquals(2, reply.updated)
        assertEquals(0, reply.missing)
        assertEquals("heartbeat accepted", reply.message)
        verify(repository).touchSessions(eq(listOf(first, second)), any())
    }

    @Test
    fun heartbeatBatchReportsMissingSessions() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(first, second)), any())).thenReturn(1)

        val request =
            PlayerHeartbeatBatchRequest.newBuilder()
                .addPlayerIds(first.toString())
                .addPlayerIds(second.toString())
                .build()

        val reply: PlayerHeartbeatBatchReply = heartbeatService.handleHeartbeatBatch(request)

        assertEquals(1, reply.updated)
        assertEquals(1, reply.missing)
        assertEquals("heartbeat accepted", reply.message)
        verify(repository).touchSessions(eq(listOf(first, second)), any())
    }
}
