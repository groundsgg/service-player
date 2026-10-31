-- Reservation does not transfer session ownership. Never rebind an admitted token,
-- even after a failed socket; its original short expiry remains authoritative.
ALTER TABLE player_session_handoffs
    ADD COLUMN admission_connection_id UUID,
    ADD COLUMN admission_proxy_id TEXT,
    ADD COLUMN admission_closed_at TIMESTAMPTZ,
    ADD CONSTRAINT handoff_admission_pair CHECK (
        (admission_connection_id IS NULL) = (admission_proxy_id IS NULL)
    );
CREATE UNIQUE INDEX player_handoff_one_admission_per_source
    ON player_session_handoffs (player_id, source_session_id)
    WHERE admission_connection_id IS NOT NULL;
CREATE INDEX player_handoff_admission_budget
    ON player_session_handoffs (admission_proxy_id, expires_at)
    WHERE admission_connection_id IS NOT NULL AND consumed_at IS NULL;
