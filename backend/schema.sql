-- Schema del database Polyglot Pocket (MySQL / PythonAnywhere)
-- utf8mb4 e' importante: serve per le lettere accentate (à, è, ì, ò, ù...).
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Utenti (REQ. 2)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,          -- MAI la password in chiaro
    native_lang   VARCHAR(3)   NOT NULL DEFAULT 'eng',  -- lingua base (prompt) = source_lang delle carte
    target_lang   VARCHAR(3)   NULL,              -- lingua scelta da imparare; NULL = non ancora scelta
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) CHARACTER SET utf8mb4;

-- ---------------------------------------------------------------------------
-- Catalogo carte (condiviso + personali)
--   owner_id NULL  -> carta del catalogo condiviso (da OMW)
--   owner_id valorizzato -> carta personale dell'utente (es. da foto, REQ. 6)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cards (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    owner_id     INT          NULL,
    source_lang  VARCHAR(3)   NOT NULL,           -- lingua madre (mostrata)
    target_lang  VARCHAR(3)   NOT NULL,           -- lingua da imparare (digitata)
    word_source  VARCHAR(100) NOT NULL,
    word_target  VARCHAR(100) NOT NULL,
    theme        VARCHAR(50)  NOT NULL,           -- usato per l'allenamento GPS (REQ. 5)
    origin       VARCHAR(20)  NOT NULL DEFAULT 'seed',  -- 'seed' | 'photo'
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_lang_theme (source_lang, target_lang, theme)
) CHARACTER SET utf8mb4;

-- ---------------------------------------------------------------------------
-- Allenamenti: un record per sessione (giuste/sbagliate/tempo)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sessions (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    user_id     INT        NOT NULL,
    mode        VARCHAR(20) NOT NULL,             -- 'random' | 'errors' | 'gps'
    target_lang VARCHAR(3)  NOT NULL,
    num_cards   INT        NOT NULL,
    num_correct INT        NOT NULL DEFAULT 0,
    num_wrong   INT        NOT NULL DEFAULT 0,
    duration_ms BIGINT     NOT NULL DEFAULT 0,
    started_at  TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4;

-- ---------------------------------------------------------------------------
-- Tentativi: un record per carta mostrata.
-- Serve a 3 cose: data di presentazione (shown_at), modalita' "impara dagli
-- errori" (is_correct = 0), e i grafici delle statistiche (REQ. 3).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attempts (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    session_id   INT          NOT NULL,
    user_id      INT          NOT NULL,
    card_id      INT          NOT NULL,
    answer_given VARCHAR(100),
    is_correct   TINYINT(1)   NOT NULL,
    similarity   FLOAT        NULL,               -- pronto per la % di somiglianza (futuro)
    shown_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
    FOREIGN KEY (card_id)    REFERENCES cards(id),
    INDEX idx_user_correct (user_id, is_correct)
) CHARACTER SET utf8mb4;
