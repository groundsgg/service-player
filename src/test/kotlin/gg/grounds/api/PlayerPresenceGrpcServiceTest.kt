package gg.grounds.api

import gg.grounds.domain.PlayerSession
import gg.grounds.grpc.player.CountPlayersByServerReply
import gg.grounds.grpc.player.CountPlayersByServerRequest
import gg.grounds.grpc.player.LoginStatus
import gg.grounds.grpc.player.PlayerLoginReply
import gg.grounds.grpc.player.PlayerLoginRequest
import gg.grounds.grpc.player.PlayerLogoutReply
import gg.grounds.grpc.player.PlayerLogoutRequest
import gg.grounds.grpc.player.PlayerPresenceService
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByServerResult
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import gg.grounds.persistence.PlayerSessionRepository.ServerPlayerCount
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.quarkus.grpc.GrpcClient
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
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
        assertNotNull(sessionCaptor.firstValue.lastSeenAt)
    }

    @Test
    fun loginReportsAlreadyOnlineWhenSessionExists() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH, Instant.now().minusSeconds(5)))

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE, reply.status)
        assertEquals("player already online", reply.message)
        verify(repository).findByPlayerId(playerId)
    }

    @Test
    fun loginAcceptsWhenExistingSessionIsStale() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false, true)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH, Instant.EPOCH))
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.REMOVED)

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ACCEPTED, reply.status)
        assertEquals("player accepted", reply.message)
        verify(repository).deleteSession(playerId)
        verify(repository, times(2)).insertSession(any())
    }

    @Test
    fun loginAcceptsWhenStaleSessionAlreadyRemoved() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false, true)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH, Instant.EPOCH))
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.NOT_FOUND)

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ACCEPTED, reply.status)
        assertEquals("player accepted", reply.message)
        verify(repository).deleteSession(playerId)
        verify(repository, times(2)).insertSession(any())
    }

    @Test
    fun loginReturnsErrorWhenStaleSessionRemovalFails() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH, Instant.EPOCH))
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.ERROR)

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ERROR, reply.status)
        assertEquals("unable to remove stale player session", reply.message)
        verify(repository).deleteSession(playerId)
        verify(repository, times(1)).insertSession(any())
    }

    @Test
    fun loginReturnsErrorWhenStaleSessionReinsertFails() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false, false)
        whenever(repository.findByPlayerId(eq(playerId)))
            .thenReturn(PlayerSession(playerId, Instant.EPOCH, Instant.EPOCH), null)
        whenever(repository.deleteSession(eq(playerId))).thenReturn(DeleteSessionResult.REMOVED)

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ERROR, reply.status)
        assertEquals("unable to create player session after stale cleanup", reply.message)
        verify(repository).deleteSession(playerId)
        verify(repository, times(2)).insertSession(any())
        verify(repository, times(2)).findByPlayerId(playerId)
    }

    @Test
    fun loginReturnsErrorWhenSessionCannotBeVerified() {
        val playerId = UUID.randomUUID()
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(eq(playerId))).thenReturn(null)

        val request = PlayerLoginRequest.newBuilder().setPlayerId(playerId.toString()).build()

        val reply: PlayerLoginReply = service.tryPlayerLogin(request).await().indefinitely()

        assertEquals(LoginStatus.LOGIN_STATUS_ERROR, reply.status)
        assertEquals("unable to verify player session", reply.message)
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

    @Test
    fun countPlayersByServerCountsTwoPlayersOnTheSameServerAsTwo() {
        whenever(repository.countPlayersByServer())
            .thenReturn(
                CountPlayersByServerResult.Counted(listOf(ServerPlayerCount("lobby", 2)), total = 2)
            )

        val reply: CountPlayersByServerReply =
            service
                .countPlayersByServer(CountPlayersByServerRequest.newBuilder().build())
                .await()
                .indefinitely()

        assertEquals(1, reply.serversList.size)
        assertEquals("lobby", reply.serversList[0].serverName)
        assertEquals(2, reply.serversList[0].players)
        assertEquals(2, reply.total)
    }

    @Test
    fun countPlayersByServerGroupsDifferentServersSeparately() {
        whenever(repository.countPlayersByServer())
            .thenReturn(
                CountPlayersByServerResult.Counted(
                    listOf(ServerPlayerCount("lobby", 1), ServerPlayerCount("arena", 1)),
                    total = 2,
                )
            )

        val reply: CountPlayersByServerReply =
            service
                .countPlayersByServer(CountPlayersByServerRequest.newBuilder().build())
                .await()
                .indefinitely()

        assertEquals(
            setOf("lobby" to 1, "arena" to 1),
            reply.serversList.map { it.serverName to it.players }.toSet(),
        )
        assertEquals(2, reply.total)
    }

    @Test
    fun countPlayersByServerCountsPlayerWithNoServerInTotalOnly() {
        whenever(repository.countPlayersByServer())
            .thenReturn(CountPlayersByServerResult.Counted(emptyList(), total = 1))

        val reply: CountPlayersByServerReply =
            service
                .countPlayersByServer(CountPlayersByServerRequest.newBuilder().build())
                .await()
                .indefinitely()

        assertTrue(reply.serversList.isEmpty())
        assertEquals(1, reply.total)
    }

    // An empty reply reads as "nobody is online anywhere" — a plausible number a caller will
    // happily
    // render. Answering that when the database is unreachable is how a proxy ends up printing a
    // count it has no business being sure of. Fail instead, so the caller can say it could not ask.
    @Test
    fun countPlayersByServerFailsWhenRepositoryFails() {
        whenever(repository.countPlayersByServer()).thenReturn(CountPlayersByServerResult.Error)

        val failure =
            assertThrows(StatusRuntimeException::class.java) {
                service
                    .countPlayersByServer(CountPlayersByServerRequest.newBuilder().build())
                    .await()
                    .indefinitely()
            }

        assertEquals(Status.Code.UNAVAILABLE, failure.status.code)
    }
}
