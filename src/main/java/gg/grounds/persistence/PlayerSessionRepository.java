package gg.grounds.persistence;

import gg.grounds.domain.PlayerSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PlayerSessionRepository {
    private static final Logger LOG = Logger.getLogger(PlayerSessionRepository.class);

    private static final String INSERT_SESSION = """
            INSERT INTO player_sessions (player_id, connected_at)
            VALUES (?, ?)
            ON CONFLICT (player_id) DO NOTHING
            """;
    private static final String SELECT_BY_PLAYER = """
            SELECT player_id, connected_at
            FROM player_sessions
            WHERE player_id = ?
            """;
    private static final String DELETE_BY_PLAYER = """
            DELETE FROM player_sessions
            WHERE player_id = ?
            """;

    @Inject
    DataSource dataSource;

    public boolean insertSession(PlayerSession session) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SESSION)) {
            statement.setObject(1, session.playerId());
            statement.setTimestamp(2, Timestamp.from(session.connectedAt()));
            return statement.executeUpdate() > 0;
        } catch (SQLException error) {
            LOG.errorf(error, "Failed to insert player session for %s", session.playerId());
            return false;
        }
    }

    public Optional<PlayerSession> findByPlayerId(UUID playerId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_PLAYER)) {
            statement.setObject(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapSession(resultSet));
            }
        } catch (SQLException error) {
            LOG.errorf(error, "Failed to fetch player session for %s", playerId);
            return Optional.empty();
        }
    }

    public boolean deleteSession(UUID playerId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_PLAYER)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() > 0;
        } catch (SQLException error) {
            LOG.errorf(error, "Failed to delete player session for %s", playerId);
            return false;
        }
    }

    private PlayerSession mapSession(ResultSet resultSet) throws SQLException {
        UUID playerId = resultSet.getObject("player_id", UUID.class);
        Instant connectedAt = resultSet.getTimestamp("connected_at").toInstant();
        return new PlayerSession(playerId, connectedAt);
    }
}
