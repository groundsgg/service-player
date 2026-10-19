-- When the session's server_name was last set from a queued server change.
--
-- Queued changes reach the core writer in stream order, but a redelivery or a
-- synchronous fallback can still apply an older one after a newer one. The
-- writer only applies a change that is not older than this, so the row always
-- ends on the newest server. NULL until the first queued change, and reset when
-- another session takes the row over. The synchronous path (flags off) never
-- touches it.
ALTER TABLE player_sessions ADD COLUMN IF NOT EXISTS server_updated_at TIMESTAMPTZ NULL;
