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

-- Errors to review. One row per (user, card): added when the answer is wrong,
-- removed when the card is later answered correctly. Stats come from the
-- sessions table, so only outstanding errors are kept here.
CREATE TABLE IF NOT EXISTS errors (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    card_id      INTEGER NOT NULL,
    answer_given TEXT,
    shown_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (user_id, card_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (card_id) REFERENCES cards(id)
);

-- One active password-reset code per local user. The 6-digit code is stored
-- hashed; a new request replaces any previous one for that user.
CREATE TABLE IF NOT EXISTS password_resets (
    user_id    INTEGER PRIMARY KEY,
    code_hash  TEXT NOT NULL,
    expires_at TEXT NOT NULL,             -- UTC "YYYY-MM-DD HH:MM:SS"
    attempts   INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
