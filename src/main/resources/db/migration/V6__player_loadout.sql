-- A player's customised kit for one game mode.
--
-- Durable per-player data, like the language on player_names: something a
-- player arranged once that has to outlive the session that produced it, and
-- outlive the game server too — a duel server is an Agones pod with no database
-- and a lifetime measured in matches.
--
-- Deliberately not a column on player_names and deliberately no foreign key to
-- it. A loadout is per (player, kit) rather than per player, and requiring a
-- durable name row first would make saving a loadout fail for reasons that have
-- nothing to do with loadouts.
--
-- `slots` is the game's own item encoding (Minecraft item stacks keyed by
-- inventory slot), stored opaquely: this service never interprets it. The game
-- server validates every stored loadout against the kit before handing it to a
-- player, so nothing here is trusted as items.
CREATE TABLE IF NOT EXISTS player_loadout (
  player_id  uuid        NOT NULL,
  -- The kit the arrangement belongs to ('pot', 'uhc', ...). Opaque to this
  -- service; the game server owns the catalogue.
  kit_id     text        NOT NULL,
  slots      jsonb       NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (player_id, kit_id)
);
