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

# Signing key for the session tokens, read from the environment (set in the
# WSGI file on PythonAnywhere). The fallback is only for local development.
SECRET_KEY = os.environ.get("SECRET_KEY", "dev-insecure-change-me")
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
            "SELECT id, username, email, display_name, native_lang, target_lang "
            "FROM users WHERE id = ?",
            (uid,),
        ).fetchone()
    finally:
        con.close()
    if row is None:
        return jsonify({"error": "unauthorized"}), 401
    return jsonify(dict(row))


@app.put("/me/target-lang")
def set_target_lang():
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401
    data = request.get_json(silent=True) or {}
    lang = (data.get("target_lang") or "").strip()

    con = get_db()
    try:
        known = con.execute("SELECT 1 FROM cards WHERE target_lang = ? LIMIT 1", (lang,)).fetchone()
        if known is None:
            return jsonify({"error": "unknown language"}), 400
        con.execute("UPDATE users SET target_lang = ? WHERE id = ?", (lang, uid))
        con.commit()
    finally:
        con.close()
    return jsonify({"target_lang": lang})


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
    """Restituisce le carte il cui ULTIMO tentativo è ancora errato."""
    user_id = uid_from_request() or int(request.args.get("user_id", 1))
    target_lang = request.args.get("target_lang", "spa").strip()

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
            JOIN attempts a ON a.card_id = c.id
            WHERE a.user_id = ?
              AND c.target_lang = ?
              AND a.id = (
                  SELECT MAX(a2.id)
                  FROM attempts a2
                  WHERE a2.card_id = c.id AND a2.user_id = ?
              )
              AND a.is_correct = 0
            ORDER BY a.shown_at DESC
            LIMIT ?
            """,
            (user_id, target_lang, user_id, n),
        ).fetchall()
    finally:
        con.close()

    return jsonify([dict(r) for r in rows])


@app.post("/sessions")
def save_session():
    """Salva il resoconto di una sessione e i singoli tentativi/errori."""
    data = request.get_json(silent=True) or {}

    user_id = uid_from_request() or int(data.get("user_id", 1))
    mode = data.get("mode", "random")
    target_lang = data.get("target_lang", "spa")
    num_cards = int(data.get("num_cards", 0))
    num_correct = int(data.get("num_correct", 0))
    num_wrong = int(data.get("num_wrong", 0))
    duration_ms = int(data.get("duration_ms", 0))
    attempts = data.get("attempts", [])

    con = get_db()
    try:
        # Inserimento della sessione
        cur = con.execute(
            """
            INSERT INTO sessions (user_id, mode, target_lang, num_cards, num_correct, num_wrong, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (user_id, mode, target_lang, num_cards, num_correct, num_wrong, duration_ms),
        )
        session_id = cur.lastrowid

        # Inserimento dei singoli tentativi per tracciare errori e statistiche
        for att in attempts:
            con.execute(
                """
                INSERT INTO attempts (session_id, user_id, card_id, answer_given, is_correct)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    session_id,
                    user_id,
                    att["card_id"],
                    att.get("answer_given", ""),
                    1 if att.get("is_correct") else 0,
                ),
            )

        con.commit()
    finally:
        con.close()

    return jsonify({"status": "ok", "session_id": session_id}), 201

if __name__ == "__main__":
    app.run(debug=True)
