-- Web profile details outlive presence and proxy connections, including accounts that have not joined yet.
CREATE TABLE player_profiles (
    player_id UUID PRIMARY KEY,
    bio VARCHAR(240) NOT NULL DEFAULT '',
    pronouns VARCHAR(24) NOT NULL DEFAULT '',
    favourite_mode VARCHAR(32),
    show_favourite_mode BOOLEAN NOT NULL DEFAULT FALSE
);
