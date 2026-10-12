-- A player's preferred (default) region, on the durable name index (never deleted),
-- so the preference outlives a session. Nullable: NULL means the player has chosen
-- none. Holds a region-catalogue code (e.g. eu, na); membership is not checked here,
-- the catalogue lives with the proxies.
ALTER TABLE player_names ADD COLUMN IF NOT EXISTS preferred_region TEXT NULL;
