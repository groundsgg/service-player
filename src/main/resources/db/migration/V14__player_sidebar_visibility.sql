-- Durable gameplay preference; existing players keep the visible default.
ALTER TABLE player_names ADD COLUMN IF NOT EXISTS sidebar_enabled BOOLEAN NOT NULL DEFAULT true;
