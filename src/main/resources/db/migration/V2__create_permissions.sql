CREATE TABLE IF NOT EXISTS permission_groups (
    name TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS group_permissions (
    group_name TEXT NOT NULL REFERENCES permission_groups(name) ON DELETE CASCADE,
    permission TEXT NOT NULL,
    PRIMARY KEY (group_name, permission)
);

CREATE TABLE IF NOT EXISTS player_groups (
    player_id UUID NOT NULL,
    group_name TEXT NOT NULL REFERENCES permission_groups(name) ON DELETE CASCADE
);

ALTER TABLE player_groups DROP CONSTRAINT IF EXISTS player_groups_pkey;
ALTER TABLE player_groups ADD PRIMARY KEY (player_id, group_name);

CREATE INDEX IF NOT EXISTS idx_player_groups_group_name ON player_groups(group_name);

CREATE TABLE IF NOT EXISTS player_permissions (
    player_id UUID NOT NULL,
    permission TEXT NOT NULL,
    PRIMARY KEY (player_id, permission)
);
