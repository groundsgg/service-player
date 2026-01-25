CREATE TABLE IF NOT EXISTS player_sessions (
    player_id UUID PRIMARY KEY,
    connected_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS player_sessions_last_seen_at_idx
    ON player_sessions (last_seen_at);
