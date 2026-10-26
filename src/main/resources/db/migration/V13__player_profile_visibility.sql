-- Who may see a player's public web profile beyond name and head: public,
-- friends or private. On the durable name index like locale and region, so it
-- outlives a session. NULL means never chosen, which readers treat as public.
ALTER TABLE player_names ADD COLUMN IF NOT EXISTS profile_visibility TEXT NULL;
