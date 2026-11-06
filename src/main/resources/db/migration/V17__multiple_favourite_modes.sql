-- Preserve the legacy primary favourite for old clients while storing the ordered list.
ALTER TABLE player_profiles ADD COLUMN favourite_modes VARCHAR(32)[] NOT NULL DEFAULT '{}';
UPDATE player_profiles SET favourite_modes = ARRAY[favourite_mode] WHERE favourite_mode IS NOT NULL;
ALTER TABLE player_profiles ADD CONSTRAINT player_profile_favourite_limit CHECK (cardinality(favourite_modes) <= 3);

-- Old binaries omit the array column. Keep their inserts/primary changes readable
-- during rolling deployment and rollback; text-only edits preserve extra modes.
CREATE FUNCTION sync_profile_favourite_modes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.favourite_modes[1] IS DISTINCT FROM NEW.favourite_mode THEN
        NEW.favourite_modes := CASE WHEN NEW.favourite_mode IS NULL
            THEN ARRAY[]::VARCHAR(32)[] ELSE ARRAY[NEW.favourite_mode] END;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sync_profile_favourite_modes BEFORE INSERT OR UPDATE ON player_profiles
FOR EACH ROW EXECUTE FUNCTION sync_profile_favourite_modes();
