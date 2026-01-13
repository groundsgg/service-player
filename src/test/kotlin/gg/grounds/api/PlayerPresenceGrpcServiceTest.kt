package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.persistence.PlayerSessionRepository
import io.quarkus.grpc.GrpcClient
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import java.util.UUID

@QuarkusTest
class PlayerPresenceGrpcServiceTest {

    @InjectMock
    lateinit var repository: PlayerSessionRepository

    @GrpcClient("player-presence")
    lateinit var service: PlayerPresenceService

    @BeforeEach
    fun resetMocks() {
        reset(repository)
    }

    @Test
    fun loginRejectsInvalidPlayerId() {
        val request = PlayerLoginRequest.newBuilder()
            .setPlayerId("not-a-uuid")
            .build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_INVALID_REQUEST, reply.status)
        assertEquals("player_id must be a UUID", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun loginAcceptsValidPlayerId() {
        val playerId = UUID.randomUUID()
        `when`(repository.insertSession(any(PlayerSession::class.java))).thenReturn(true)

        val request = PlayerLoginRequest.newBuilder()
            .setPlayerId("  $playerId ")
            .build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ACCEPTED, reply.status)
        assertEquals("player accepted", reply.message)

        val sessionCaptor: ArgumentCaptor<PlayerSession> = ArgumentCaptor.forClass(PlayerSession::class.java)
        verify(repository).insertSession(sessionCaptor.capture())
        assertEquals(playerId, sessionCaptor.value.playerId)
        assertNotNull(sessionCaptor.value.connectedAt)
    }

    @Test
    fun loginReportsAlreadyOnlineWhenSessionExists() {
        val playerId = UUID.randomUUID()
        `when`(repository.insertSession(any(PlayerSession::class.java))).thenReturn(false)
        `when`(repository.findByPlayerId(eq(playerId)))
            .thenReturn(Optional.of(PlayerSession(playerId, Instant.EPOCH)))

        val request = PlayerLoginRequest.newBuilder()
            .setPlayerId(playerId.toString())
            .build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE, reply.status)
        assertEquals("player already online", reply.message)
        verify(repository).findByPlayerId(playerId)
    }

    @Test
    fun logoutRejectsInvalidPlayerId() {
        val request = PlayerLogoutRequest.newBuilder()
            .setPlayerId("bad-id")
            .build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertFalse(reply.removed)
        assertEquals("player_id must be a UUID", reply.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun logoutRemovesSessionWhenFound() {
        val playerId = UUID.randomUUID()
        `when`(repository.deleteSession(eq(playerId))).thenReturn(true)

        val request = PlayerLogoutRequest.newBuilder()
            .setPlayerId(" $playerId ")
            .build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertTrue(reply.removed)
        assertEquals("player removed", reply.message)
        verify(repository).deleteSession(playerId)
    }

    @Test
    fun logoutReturnsNotFoundWhenMissing() {
        val playerId = UUID.randomUUID()
        `when`(repository.deleteSession(eq(playerId))).thenReturn(false)

        val request = PlayerLogoutRequest.newBuilder()
            .setPlayerId(playerId.toString())
            .build()

        val reply: PlayerLogoutReply = service.playerLogout(request).await().indefinitely()

        assertFalse(reply.removed)
        assertEquals("player session not found", reply.message)
        verify(repository).deleteSession(playerId)
    }
}
