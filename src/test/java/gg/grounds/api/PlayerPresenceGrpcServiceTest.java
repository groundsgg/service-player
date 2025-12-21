package gg.grounds.api;

import gg.grounds.domain.PlayerSession;
import gg.grounds.grpc.*;
import gg.grounds.persistence.PlayerSessionRepository;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class PlayerPresenceGrpcServiceTest {

    @InjectMock
    PlayerSessionRepository repository;

    @GrpcClient("player-presence")
    PlayerPresenceService service;

    @BeforeEach
    void resetMocks() {
        reset(repository);
    }

    @Test
    void loginRejectsInvalidPlayerId() {
        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
                .setPlayerId("not-a-uuid")
                .build();

        PlayerLoginReply reply = service.tryPlayerLogin(request).await().indefinitely();

        assertEquals(LoginStatus.LOGIN_STATUS_INVALID_REQUEST, reply.getStatus());
        assertEquals("player_id must be a UUID", reply.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void loginAcceptsValidPlayerId() {
        UUID playerId = UUID.randomUUID();
        when(repository.insertSession(any(PlayerSession.class))).thenReturn(true);

        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
                .setPlayerId("  " + playerId + " ")
                .build();

        PlayerLoginReply reply = service.tryPlayerLogin(request).await().indefinitely();

        assertEquals(LoginStatus.LOGIN_STATUS_ACCEPTED, reply.getStatus());
        assertEquals("player accepted", reply.getMessage());

        ArgumentCaptor<PlayerSession> sessionCaptor = ArgumentCaptor.forClass(PlayerSession.class);
        verify(repository).insertSession(sessionCaptor.capture());
        assertEquals(playerId, sessionCaptor.getValue().playerId());
        assertNotNull(sessionCaptor.getValue().connectedAt());
    }

    @Test
    void loginReportsAlreadyOnlineWhenSessionExists() {
        UUID playerId = UUID.randomUUID();
        when(repository.insertSession(any(PlayerSession.class))).thenReturn(false);
        when(repository.findByPlayerId(eq(playerId)))
                .thenReturn(Optional.of(new PlayerSession(playerId, Instant.EPOCH)));

        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .build();

        PlayerLoginReply reply = service.tryPlayerLogin(request).await().indefinitely();

        assertEquals(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE, reply.getStatus());
        assertEquals("player already online", reply.getMessage());
        verify(repository).findByPlayerId(playerId);
    }

    @Test
    void logoutRejectsInvalidPlayerId() {
        PlayerLogoutRequest request = PlayerLogoutRequest.newBuilder()
                .setPlayerId("bad-id")
                .build();

        PlayerLogoutReply reply = service.playerLogout(request).await().indefinitely();

        assertFalse(reply.getRemoved());
        assertEquals("player_id must be a UUID", reply.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void logoutRemovesSessionWhenFound() {
        UUID playerId = UUID.randomUUID();
        when(repository.deleteSession(eq(playerId))).thenReturn(true);

        PlayerLogoutRequest request = PlayerLogoutRequest.newBuilder()
                .setPlayerId(" " + playerId + " ")
                .build();

        PlayerLogoutReply reply = service.playerLogout(request).await().indefinitely();

        assertTrue(reply.getRemoved());
        assertEquals("player removed", reply.getMessage());
        verify(repository).deleteSession(playerId);
    }

    @Test
    void logoutReturnsNotFoundWhenMissing() {
        UUID playerId = UUID.randomUUID();
        when(repository.deleteSession(eq(playerId))).thenReturn(false);

        PlayerLogoutRequest request = PlayerLogoutRequest.newBuilder()
                .setPlayerId(playerId.toString())
                .build();

        PlayerLogoutReply reply = service.playerLogout(request).await().indefinitely();

        assertFalse(reply.getRemoved());
        assertEquals("player session not found", reply.getMessage());
        verify(repository).deleteSession(playerId);
    }
}
