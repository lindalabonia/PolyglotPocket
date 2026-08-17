-- Schema del database Polyglot Pocket (SQLite)
-- SQLite e' UTF-8 nativo: nessuna dichiarazione di charset necessaria.
-- Le chiavi esterne vanno abilitate a runtime con: PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- Utenti (REQ. 2)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,               -- MAI la password in chiaro
    native_lang   TEXT NOT NULL DEFAULT 'eng', -- lingua base (prompt) = source_lang delle carte
    target_lang   TEXT,                         -- lingua scelta da imparare; NULL = non ancora scelta
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ---------------------------------------------------------------------------
-- Catalogo carte (condiviso + personali)
--   owner_id NULL           -> carta del catalogo condiviso (da OMW)
--   owner_id valorizzato     -> carta personale dell'utente (es. da foto, REQ. 6)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cards (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id     INTEGER,
    source_lang  TEXT NOT NULL,                 -- lingua base (mostrata)
    target_lang  TEXT NOT NULL,                 -- lingua da imparare (digitata)
    word_source  TEXT NOT NULL,
    word_target  TEXT NOT NULL,
    theme        TEXT NOT NULL,                 -- usato per l'allenamento GPS (REQ. 5)
    origin       TEXT NOT NULL DEFAULT 'seed',  -- 'seed' | 'photo'
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cards_lang_theme ON cards (source_lang, target_lang, theme);

-- ---------------------------------------------------------------------------
-- Allenamenti: un record per sessione (giuste/sbagliate/tempo)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    mode        TEXT NOT NULL,                  -- 'random' | 'errors' | 'gps'
    target_lang TEXT NOT NULL,
    num_cards   INTEGER NOT NULL,
    num_correct INTEGER NOT NULL DEFAULT 0,
    num_wrong   INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    started_at  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- Tentativi: un record per carta mostrata.
-- Serve a 3 cose: data di presentazione (shown_at), modalita' "impara dagli
-- errori" (is_correct = 0), e i grafici delle statistiche (REQ. 3).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   INTEGER NOT NULL,
    user_id      INTEGER NOT NULL,
    card_id      INTEGER NOT NULL,
    answer_given TEXT,
    is_correct   INTEGER NOT NULL,              -- 0/1
    similarity   REAL,                          -- pronto per la % di somiglianza (futuro)
    shown_at     TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
    FOREIGN KEY (card_id)    REFERENCES cards(id)
);
CREATE INDEX IF NOT EXISTS idx_attempts_user_correct ON attempts (user_id, is_correct);
