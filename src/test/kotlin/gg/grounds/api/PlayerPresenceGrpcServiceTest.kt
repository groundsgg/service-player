package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import io.quarkus.grpc.GrpcClient
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@QuarkusTest
class PlayerPresenceGrpcServiceTest {

    @InjectMock lateinit var repository: PlayerSessionRepository

    @GrpcClient("player-presence") lateinit var service: PlayerPresenceService

    @BeforeEach
    fun resetMocks() {
        reset(repository)
    }

    @Test
    fun loginRejectsInvalidPlayerId() {
        val request = PlayerLoginRequest.newBuilder().setPlayerId("not-a-uuid").build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_INVALID_REQUEST, reply.status)
        assertEquals("player_id must be a UUID", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun loginAcceptsValidPlayerId() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(true)

        val request = PlayerLoginRequest.newBuilder().setPlayerId("  $playerId ").build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ACCEPTED, reply.status)
        assertEquals("player accepted", reply.message)

        val sessionCaptor = argumentCaptor<PlayerSession>()
        verify(repository).insertSession(sessionCaptor.capture())
        assertEquals(playerId, sessionCaptor.firstValue.playerId)
        assertNotNull(sessionCaptor.firstValue.connectedAt)
    }

    @Test
    fun loginReportsAlreadyOnlineWhenSessionExists() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH))

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE, reply.status)
        assertEquals("player already online", reply.message)
        verify(repository).findByPlayerId(playerId)
    }

    @Test
    fun logoutRejectsInvalidPlayerId() {
        val request = PlayerLogoutRequest.newBuilder().setPlayerId("bad-id").build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertFalse(reply.removed)
        assertEquals("player_id must be a UUID", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun logoutRemovesSessionWhenFound() {
        val playerId = UUID.randomUUID()
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.REMOVED)

        val request = PlayerLogoutRequest.newBuilder().setPlayerId(" $playerId ").build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertTrue(reply.removed)
        assertEquals("player removed", reply.message)
        verify(repository).deleteSession(playerId)
    }

    @Test
    fun logoutReturnsNotFoundWhenMissing() {
        val playerId = UUID.randomUUID()
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.NOT_FOUND)

        val request = PlayerLogoutRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertFalse(reply.removed)
        assertEquals("player session not found", reply.message)
        verify(repository).deleteSession(playerId)
    }
}
