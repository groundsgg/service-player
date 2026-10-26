-- Durable prefix/exact-name lookup used by staff moderation when a profile is offline.
-- Names are deliberately not unique: reclaimed names must be reported as ambiguous.
CREATE INDEX IF NOT EXISTS player_names_player_name_lower_idx
    ON player_names (lower(player_name) text_pattern_ops);
