package gg.grounds.domain;

import java.time.Instant;
import java.util.UUID;

public record PlayerSession(UUID playerId, Instant connectedAt) {
}
