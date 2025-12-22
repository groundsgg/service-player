package gg.grounds.api;

import gg.grounds.domain.PlayerSession;
import gg.grounds.grpc.player.LoginStatus;
import gg.grounds.grpc.player.PlayerLoginReply;
import gg.grounds.grpc.player.PlayerLoginRequest;
import gg.grounds.grpc.player.PlayerLogoutReply;
import gg.grounds.grpc.player.PlayerLogoutRequest;
import gg.grounds.grpc.player.PlayerPresenceService;
import gg.grounds.persistence.PlayerSessionRepository;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@GrpcService
@Blocking
public class PlayerPresenceGrpcService implements PlayerPresenceService {
    private static final Logger LOG = Logger.getLogger(PlayerPresenceGrpcService.class);

    @Inject
    PlayerSessionRepository repository;

    @Override
    public Uni<PlayerLoginReply> tryPlayerLogin(PlayerLoginRequest request) {
        return Uni.createFrom().item(() -> handleLogin(request));
    }

    @Override
    public Uni<PlayerLogoutReply> playerLogout(PlayerLogoutRequest request) {
        return Uni.createFrom().item(() -> handleLogout(request));
    }

    private PlayerLoginReply handleLogin(PlayerLoginRequest request) {
        Optional<UUID> playerId = parsePlayerId(request.getPlayerId());
        if (playerId.isEmpty()) {
            return PlayerLoginReply.newBuilder()
                    .setStatus(LoginStatus.LOGIN_STATUS_INVALID_REQUEST)
                    .setMessage("player_id must be a UUID")
                    .build();
        }

        UUID playerUuid = playerId.get();
        PlayerSession session = new PlayerSession(playerUuid, Instant.now());
        boolean inserted = repository.insertSession(session);
        if (inserted) {
            LOG.infof("Player %s logged in", playerUuid);
            return PlayerLoginReply.newBuilder()
                    .setStatus(LoginStatus.LOGIN_STATUS_ACCEPTED)
                    .setMessage("player accepted")
                    .build();
        }

        Optional<PlayerSession> existing = repository.findByPlayerId(playerUuid);
        if (existing.isPresent()) {
            LOG.infof("Player %s rejected: already online", playerUuid);
            return PlayerLoginReply.newBuilder()
                    .setStatus(LoginStatus.LOGIN_STATUS_ALREADY_ONLINE)
                    .setMessage("player already online")
                    .build();
        }

        return PlayerLoginReply.newBuilder()
                .setStatus(LoginStatus.LOGIN_STATUS_ERROR)
                .setMessage("unable to verify player session")
                .build();
    }

    private PlayerLogoutReply handleLogout(PlayerLogoutRequest request) {
        Optional<UUID> playerId = parsePlayerId(request.getPlayerId());
        if (playerId.isEmpty()) {
            return PlayerLogoutReply.newBuilder()
                    .setRemoved(false)
                    .setMessage("player_id must be a UUID")
                    .build();
        }

        UUID playerUuid = playerId.get();
        boolean removed = repository.deleteSession(playerUuid);
        if (removed) {
            LOG.infof("Player %s logged out", playerUuid);
        }

        return PlayerLogoutReply.newBuilder()
                .setRemoved(removed)
                .setMessage(removed ? "player removed" : "player session not found")
                .build();
    }

    private Optional<UUID> parsePlayerId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(trimmed));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
