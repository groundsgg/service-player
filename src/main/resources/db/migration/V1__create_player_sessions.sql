CREATE TABLE IF NOT EXISTS player_sessions (
    player_id UUID PRIMARY KEY,
    connected_at TIMESTAMPTZ NOT NULL
);
