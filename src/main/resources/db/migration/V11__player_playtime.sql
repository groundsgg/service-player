-- Playtime: how long each player has been online, accumulated across sessions.
--
-- total_millis is time with a session on any server, the lobby and the time
-- between servers included. play_millis is the part of it spent on game servers.
-- Durable like the name index: rows are never deleted with the session.
CREATE TABLE player_playtime (
    player_id UUID PRIMARY KEY,
    total_millis BIGINT NOT NULL DEFAULT 0,
    play_millis BIGINT NOT NULL DEFAULT 0
);

-- When the session's current segment began: the connection, or the last change
-- of server. Existing sessions start counting from their connection.
ALTER TABLE player_sessions ADD COLUMN server_since TIMESTAMPTZ NULL;
UPDATE player_sessions SET server_since = connected_at;
ALTER TABLE player_sessions ALTER COLUMN server_since SET NOT NULL;

-- Which servers count as play time. Server names are Agones GameServer names,
-- which carry their Fleet's name as prefix: every lobby is `lobby-…` (plugin-lobby
-- finds its peers the same way, GROUNDS_LOBBY_SERVER_PREFIX). No server yet
-- (NULL) is the time between joining and reaching a backend, or between two
-- backends. The buildserver is where maps are built, not played. Everything
-- else is a game.
CREATE FUNCTION player_server_is_game(server_name TEXT) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE
    AS $$ SELECT server_name IS NOT NULL AND lower(server_name) NOT LIKE 'lobby%'
        AND lower(server_name) NOT LIKE 'buildserver%' $$;

-- Credits the segment a session row is closing.
--
-- A trigger rather than a clause in each statement: a segment ends wherever a
-- row's server changes, its connection is replaced, or the row goes away, and
-- that is a dozen statements across the synchronous API, the core writer, the
-- handoff and the stale cleanup. Here, none of them can forget it.
--
-- A segment ends now, but never later than 90 s (grounds.player.sessions.ttl)
-- after the last heartbeat: a proxy that crashed stops heartbeating, and its
-- players must not keep earning time until the cleanup notices.
CREATE FUNCTION player_playtime_credit() RETURNS TRIGGER
    LANGUAGE plpgsql
    AS $$
DECLARE
    credited BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.server_since := NEW.connected_at;
        RETURN NEW;
    END IF;

    credited := GREATEST(
        0,
        (EXTRACT(EPOCH FROM LEAST(now(), OLD.last_seen_at + INTERVAL '90 seconds') - OLD.server_since) * 1000)::BIGINT
    );
    INSERT INTO player_playtime (player_id, total_millis, play_millis)
    VALUES (
        OLD.player_id,
        credited,
        CASE WHEN player_server_is_game(OLD.server_name) THEN credited ELSE 0 END
    )
    ON CONFLICT (player_id) DO UPDATE
    SET total_millis = player_playtime.total_millis + EXCLUDED.total_millis,
        play_millis = player_playtime.play_millis + EXCLUDED.play_millis;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- A new connection starts its segment when it connected; a server change, now.
    NEW.server_since := CASE
        WHEN NEW.connected_at = OLD.connected_at THEN now()
        ELSE NEW.connected_at
    END;
    RETURN NEW;
END
$$;

CREATE TRIGGER player_sessions_playtime_insert
    BEFORE INSERT ON player_sessions
    FOR EACH ROW EXECUTE FUNCTION player_playtime_credit();

-- Only a server change or a new connection closes a segment. The column list and
-- WHEN keep the heartbeat, which only moves last_seen_at, from firing it at all.
CREATE TRIGGER player_sessions_playtime_update
    BEFORE UPDATE OF server_name, connected_at ON player_sessions
    FOR EACH ROW
    WHEN (OLD.server_name IS DISTINCT FROM NEW.server_name
          OR OLD.connected_at IS DISTINCT FROM NEW.connected_at)
    EXECUTE FUNCTION player_playtime_credit();

CREATE TRIGGER player_sessions_playtime_delete
    BEFORE DELETE ON player_sessions
    FOR EACH ROW EXECUTE FUNCTION player_playtime_credit();
