-- Where the proxy holding this session is.
--
-- Nullable, and it stays nullable: a proxy that declares no region still gets a
-- session, and every session that already exists when this deploys has no
-- region either. Backfilling would mean inventing a location for players whose
-- proxy never told us one.
ALTER TABLE player_sessions ADD COLUMN IF NOT EXISTS region TEXT;

-- Counting players per proxy groups on (proxy_id, region) over the whole table.
-- That is a full scan by nature, but the index keeps it cheap once the table is
-- large, and /online is a per-command call rather than a per-keystroke one.
CREATE INDEX IF NOT EXISTS player_sessions_proxy_region_idx
    ON player_sessions (proxy_id, region);
