-- Polyglot Pocket database (SQLite).
-- Foreign keys must be enabled per connection: PRAGMA foreign_keys = ON;

-- A user is either local (username + password_hash) or google (google_sub + email).
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT UNIQUE,                 -- local only
    password_hash TEXT,                        -- local only, never the plain password
    google_sub    TEXT UNIQUE,                 -- google only, stable Google user id
    email         TEXT,
    display_name  TEXT,
    auth_provider TEXT NOT NULL,               -- 'local' | 'google'
    native_lang   TEXT NOT NULL DEFAULT 'eng',
    target_lang   TEXT,                        -- language being learned, NULL until picked
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- owner_id NULL = shared catalog (from OMW); set = the user's own card (e.g. from a photo).
CREATE TABLE IF NOT EXISTS cards (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id     INTEGER,
    source_lang  TEXT NOT NULL,
    target_lang  TEXT NOT NULL,
    word_source  TEXT NOT NULL,
    word_target  TEXT NOT NULL,
    theme        TEXT NOT NULL,
    origin       TEXT NOT NULL DEFAULT 'seed',
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cards_lang_theme ON cards (source_lang, target_lang, theme);

CREATE TABLE IF NOT EXISTS sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    mode        TEXT NOT NULL,                 -- 'random' | 'errors' | 'gps'
    target_lang TEXT NOT NULL,
    num_cards   INTEGER NOT NULL,
    num_correct INTEGER NOT NULL DEFAULT 0,
    num_wrong   INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    started_at  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- One row per card shown: feeds the stats and the "review mistakes" mode.
CREATE TABLE IF NOT EXISTS attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   INTEGER NOT NULL,
    user_id      INTEGER NOT NULL,
    card_id      INTEGER NOT NULL,
    answer_given TEXT,
    is_correct   INTEGER NOT NULL,
    similarity   REAL,
    shown_at     TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
    FOREIGN KEY (card_id)    REFERENCES cards(id)
);
CREATE INDEX IF NOT EXISTS idx_attempts_user_correct ON attempts (user_id, is_correct);
