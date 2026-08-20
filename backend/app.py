"""Polyglot Pocket REST backend."""

import hashlib
import os
import sqlite3
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from flask import Flask, jsonify, request
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

app = Flask(__name__)

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "polyglot.db")
MAX_CARDS = 100

# Signing key for the session tokens. Must be set in the environment (the WSGI
# file on PythonAnywhere). A missing key is a hard error in production, never a
# weak default; for local development set POLYGLOT_DEV=1 to allow an insecure key.
SECRET_KEY = os.environ.get("SECRET_KEY")
if not SECRET_KEY:
    if os.environ.get("POLYGLOT_DEV") == "1":
        SECRET_KEY = "dev-insecure-key"
    else:
        raise RuntimeError(
            "SECRET_KEY is not set. Set it in the WSGI file, or POLYGLOT_DEV=1 for local dev."
        )
GOOGLE_WEB_CLIENT_ID = os.environ.get("GOOGLE_WEB_CLIENT_ID", "")
TOKEN_TTL = timedelta(days=7)

ph = PasswordHasher()


def get_db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    return con


def make_token(uid):
    now = datetime.now(timezone.utc)
    payload = {"sub": str(uid), "iat": now, "exp": now + TOKEN_TTL}
    return jwt.encode(payload, SECRET_KEY, algorithm="HS256")


def uid_from_request():
    """User id from the Bearer token, or None if missing/invalid/expired."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    try:
        payload = jwt.decode(auth[7:], SECRET_KEY, algorithms=["HS256"])
        return int(payload["sub"])
    except (jwt.InvalidTokenError, KeyError, ValueError):
        return None


# --- Auth ---------------------------------------------------------------

@app.post("/auth/register")
def register():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    if len(username) < 3 or len(password) < 6:
        return jsonify({"error": "username min 3 chars, password min 6"}), 400

    con = get_db()
    try:
        cur = con.execute(
            "INSERT INTO users (username, password_hash, auth_provider) VALUES (?, ?, 'local')",
            (username, ph.hash(password)),
        )
        con.commit()
        uid = cur.lastrowid
    except sqlite3.IntegrityError:
        return jsonify({"error": "username already taken"}), 409
    finally:
        con.close()

    return jsonify({"token": make_token(uid), "user": {"id": uid, "username": username}}), 201


@app.post("/auth/login")
def login():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""

    con = get_db()
    try:
        row = con.execute(
            "SELECT id, password_hash FROM users WHERE username = ? AND auth_provider = 'local'",
            (username,),
        ).fetchone()
    finally:
        con.close()

    # Same response whether the user is missing or the password is wrong.
    if row is None or row["password_hash"] is None:
        return jsonify({"error": "invalid credentials"}), 401
    try:
        ph.verify(row["password_hash"], password)
    except VerifyMismatchError:
        return jsonify({"error": "invalid credentials"}), 401

    return jsonify({"token": make_token(row["id"]), "user": {"id": row["id"], "username": username}})


@app.post("/auth/google")
def auth_google():
    data = request.get_json(silent=True) or {}
    token = data.get("id_token") or ""
    raw_nonce = data.get("nonce") or ""
    if not token:
        return jsonify({"error": "missing id_token"}), 400

    # Verifies signature, expiry, issuer and audience (== our web client id).
    try:
        info = google_id_token.verify_oauth2_token(
            token, google_requests.Request(), GOOGLE_WEB_CLIENT_ID
        )
    except ValueError:
        return jsonify({"error": "invalid Google token"}), 401

    # The token's nonce must be SHA-256 of the raw nonce the app generated.
    expected_nonce = hashlib.sha256(raw_nonce.encode()).hexdigest()
    if not raw_nonce or info.get("nonce") != expected_nonce:
        return jsonify({"error": "invalid nonce"}), 401

    sub = info["sub"]
    email = info.get("email")
    name = info.get("name") or email or "user"

    con = get_db()
    try:
        row = con.execute("SELECT id FROM users WHERE google_sub = ?", (sub,)).fetchone()
        if row is None:
            cur = con.execute(
                "INSERT INTO users (google_sub, email, display_name, auth_provider) "
                "VALUES (?, ?, ?, 'google')",
                (sub, email, name),
            )
            con.commit()
            uid = cur.lastrowid
        else:
            uid = row["id"]
    finally:
        con.close()

    return jsonify({"token": make_token(uid), "user": {"id": uid, "username": name}})


@app.get("/me")
def me():
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401
    con = get_db()
    try:
        row = con.execute(
            "SELECT id, username, email, display_name, native_lang "
            "FROM users WHERE id = ?",
            (uid,),
        ).fetchone()
    finally:
        con.close()
    if row is None:
        return jsonify({"error": "unauthorized"}), 401
    return jsonify(dict(row))


# --- Read-only endpoints ------------------------------------------------

@app.get("/")
@app.get("/ping")
def ping():
    return jsonify({"status": "ok"})


@app.get("/languages")
def languages():
    con = get_db()
    try:
        rows = con.execute(
            "SELECT DISTINCT target_lang FROM cards ORDER BY target_lang"
        ).fetchall()
    finally:
        con.close()
    return jsonify([r["target_lang"] for r in rows])


@app.get("/cards")
def cards():
    target_lang = request.args.get("target_lang", "").strip()
    if not target_lang:
        return jsonify({"error": "missing 'target_lang'"}), 400

    try:
        n = int(request.args.get("n", 10))
    except ValueError:
        return jsonify({"error": "invalid 'n'"}), 400
    n = max(1, min(n, MAX_CARDS))

    con = get_db()
    try:
        rows = con.execute(
            """
            SELECT id, word_source, word_target, theme
            FROM cards
            WHERE target_lang = ? AND owner_id IS NULL
            ORDER BY RANDOM()
            LIMIT ?
            """,
            (target_lang, n),
        ).fetchall()
    finally:
        con.close()

    return jsonify([dict(r) for r in rows])

# --- Training & Sessions ------------------------------------------------

@app.get("/cards/errors")
def cards_errors():
    """Random selection of the cards the user still has wrong (the errors queue)."""
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401
    target_lang = request.args.get("target_lang", "").strip()

    try:
        n = int(request.args.get("n", 10))
    except ValueError:
        n = 10
    n = max(1, min(n, MAX_CARDS))

    con = get_db()
    try:
        rows = con.execute(
            """
            SELECT c.id, c.word_source, c.word_target, c.theme
            FROM cards c
            JOIN errors a ON a.card_id = c.id
            WHERE a.user_id = ? AND c.target_lang = ?
            ORDER BY RANDOM()
            LIMIT ?
            """,
            (uid, target_lang, n),
        ).fetchall()
    finally:
        con.close()

    return jsonify([dict(r) for r in rows])


@app.post("/sessions")
def save_session():
    """Save the session summary and update the errors queue: a wrong card is
    added, a correct card is removed."""
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401

    data = request.get_json(silent=True) or {}
    mode = data.get("mode", "random")
    target_lang = data.get("target_lang", "spa")
    try:
        num_cards = int(data.get("num_cards", 0))
        num_correct = int(data.get("num_correct", 0))
        num_wrong = int(data.get("num_wrong", 0))
        duration_ms = int(data.get("duration_ms", 0))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid session data"}), 400
    attempts = data.get("attempts", [])

    con = get_db()
    try:
        cur = con.execute(
            """
            INSERT INTO sessions (user_id, mode, target_lang, num_cards, num_correct, num_wrong, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (uid, mode, target_lang, num_cards, num_correct, num_wrong, duration_ms),
        )
        session_id = cur.lastrowid

        for att in attempts:
            card_id = att.get("card_id")
            if card_id is None:
                continue  # skip a malformed attempt instead of crashing
            if att.get("is_correct"):
                # Answered correctly: clear it from the errors queue.
                con.execute(
                    "DELETE FROM errors WHERE user_id = ? AND card_id = ?",
                    (uid, card_id),
                )
            else:
                # Wrong: add or refresh it in the errors queue (one row per card).
                con.execute(
                    """
                    INSERT INTO errors (user_id, card_id, answer_given)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id, card_id)
                    DO UPDATE SET answer_given = excluded.answer_given, shown_at = datetime('now')
                    """,
                    (uid, card_id, att.get("answer_given", "")),
                )

        con.commit()
    finally:
        con.close()

    return jsonify({"status": "ok", "session_id": session_id}), 201

if __name__ == "__main__":
    app.run(debug=True)
