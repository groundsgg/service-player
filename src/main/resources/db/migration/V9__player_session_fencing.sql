ALTER TABLE player_sessions ADD COLUMN session_id UUID NULL;

CREATE UNIQUE INDEX player_sessions_session_id_idx
    ON player_sessions (session_id)
    WHERE session_id IS NOT NULL;

CREATE TABLE player_session_handoffs (
    token_hash BYTEA PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES player_sessions (player_id) ON DELETE CASCADE,
    source_session_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL
);

CREATE INDEX player_session_handoffs_expiry_idx
    ON player_session_handoffs (expires_at);
