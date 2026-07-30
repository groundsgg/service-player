-- A player's chosen interface language, on the durable name index (never deleted),
-- so the preference outlives a session. Nullable: NULL means the player has chosen
-- none and the client's announced locale is used. Set by the in-game /lang command.
ALTER TABLE player_names ADD COLUMN IF NOT EXISTS locale TEXT;
