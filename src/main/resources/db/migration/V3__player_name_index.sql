-- The durable name index. Unlike player_sessions (presence — the row is deleted on
-- logout), this table is never deleted: it is the only place a player's name survives
-- after they disconnect. Written on every login attempt, so anything that outlives a
-- session (a leaderboard, match history, a ban list, ...) can show a name instead of a
-- raw uuid.
CREATE TABLE IF NOT EXISTS player_names (
    player_id UUID PRIMARY KEY,
    player_name TEXT NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);
