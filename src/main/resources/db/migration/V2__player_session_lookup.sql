ALTER TABLE player_sessions
    ADD COLUMN IF NOT EXISTS player_name TEXT,
    ADD COLUMN IF NOT EXISTS proxy_id TEXT,
    ADD COLUMN IF NOT EXISTS server_name TEXT;

CREATE INDEX IF NOT EXISTS player_sessions_player_name_lower_idx
    ON player_sessions (lower(player_name) text_pattern_ops);
