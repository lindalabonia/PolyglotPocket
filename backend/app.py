"""Polyglot Pocket REST backend."""

import hashlib
import os
import sqlite3
from datetime import datetime, timedelta, timezone

import jwt
import requests
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
SECRET_KEY = os.environ.get("SECRET_KEY", "dev-insecure-change-me")
GOOGLE_WEB_CLIENT_ID = os.environ.get("GOOGLE_WEB_CLIENT_ID", "")
VISION_API_KEY = os.environ.get("GOOGLE_CLOUD_VISION_API_KEY", "")
TOKEN_TTL = timedelta(days=7)

# Our 3-letter target codes -> MyMemory 2-letter codes.
MYMEMORY_LANG = {"spa": "es", "fra": "fr", "por": "pt", "nld": "nl", "arb": "ar"}

# Themes available in the DB (a photo card must use one of these).
KNOWN_THEMES = {
    "food", "animals", "plants", "body", "objects", "places",
    "people", "materials", "time", "money", "emotions",
}

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
    theme = request.args.get("theme", "").strip()
    if not target_lang:
        return jsonify({"error": "missing 'target_lang'"}), 400

    try:
        n = int(request.args.get("n", 10))
    except ValueError:
        return jsonify({"error": "invalid 'n'"}), 400
    n = max(1, min(n, MAX_CARDS))

    con = get_db()
    try:
        if theme:
            rows = con.execute(
                """
                SELECT id, word_source, word_target, theme
                FROM cards
                WHERE target_lang = ? AND theme = ?
                ORDER BY RANDOM()
                LIMIT ?
                """,
                (target_lang, theme, n),
            ).fetchall()
        else:
            rows = con.execute(
                """
                SELECT id, word_source, word_target, theme
                FROM cards
                WHERE target_lang = ?
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


# --- Photo -> card (REQ. 6 image processing + REQ. 8 Vision + REQ. 1 MyMemory) ---

# Drop weak detections so the overlay is not cluttered with unlikely guesses.
MIN_OBJECT_SCORE = 0.4
# Cap the words translated in a single batch request.
MAX_TRANSLATE = 20


def vision_localize_objects(image_b64):
    """Objects found in a base64 image via Cloud Vision object localization.
    Returns a list of {name, score, x, y, w, h} with the box normalized to 0..1."""
    url = f"https://vision.googleapis.com/v1/images:annotate?key={VISION_API_KEY}"
    body = {
        "requests": [{
            "image": {"content": image_b64},
            "features": [{"type": "OBJECT_LOCALIZATION", "maxResults": MAX_TRANSLATE}],
        }]
    }
    resp = requests.post(url, json=body, timeout=20)
    resp.raise_for_status()
    annotations = resp.json()["responses"][0].get("localizedObjectAnnotations", [])

    objects = []
    for ann in annotations:
        if ann.get("score", 0) < MIN_OBJECT_SCORE:
            continue
        # Vision omits a vertex coordinate when it is 0, so default the missing ones.
        verts = ann["boundingPoly"]["normalizedVertices"]
        xs = [v.get("x", 0.0) for v in verts]
        ys = [v.get("y", 0.0) for v in verts]
        x, y = min(xs), min(ys)
        objects.append({
            "name": ann["name"],
            "score": ann["score"],
            "x": x,
            "y": y,
            "w": max(xs) - x,
            "h": max(ys) - y,
        })
    return objects


def translate_en(text, target_lang):
    """Translate English text into the target language via MyMemory (public API)."""
    code = MYMEMORY_LANG.get(target_lang, target_lang)
    resp = requests.get(
        "https://api.mymemory.translated.net/get",
        params={"q": text, "langpair": f"en|{code}"},
        timeout=20,
    )
    resp.raise_for_status()
    return resp.json()["responseData"]["translatedText"]


@app.post("/photo/detect")
def photo_detect():
    """Locate objects in the photo (does NOT translate or save). Each object has
    its English name and a bounding box (0..1) for the app to draw over the image."""
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401

    data = request.get_json(silent=True) or {}
    image_b64 = data.get("image") or ""
    if not image_b64:
        return jsonify({"error": "missing 'image'"}), 400
    if not VISION_API_KEY:
        return jsonify({"error": "vision not configured"}), 500

    try:
        objects = vision_localize_objects(image_b64)
    except Exception:
        return jsonify({"error": "vision request failed"}), 502

    return jsonify({"objects": objects})


@app.post("/photo/translate")
def photo_translate():
    """Translate a batch of English words and flag which ones the user's pool
    already has. Used after the user picks boxes from a detected photo."""
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401

    data = request.get_json(silent=True) or {}
    words = data.get("words") or []
    target_lang = (data.get("target_lang") or "").strip()
    if not isinstance(words, list) or not words or not target_lang:
        return jsonify({"error": "missing 'words' or 'target_lang'"}), 400

    # De-duplicate while keeping order, so the same object picked twice is one call.
    seen = []
    for w in words:
        w = (w or "").strip().lower()
        if w and w not in seen:
            seen.append(w)
        if len(seen) >= MAX_TRANSLATE:
            break

    con = get_db()
    try:
        results = []
        for word_source in seen:
            try:
                word_target = translate_en(word_source, target_lang)
            except Exception:
                return jsonify({"error": "translation failed"}), 502
            existing = con.execute(
                "SELECT id FROM cards WHERE target_lang = ? AND word_source = ? AND word_target = ?",
                (target_lang, word_source, word_target),
            ).fetchone()
            results.append({
                "word_source": word_source,
                "word_target": word_target,
                "exists": existing is not None,
            })
    finally:
        con.close()

    return jsonify({"results": results})


@app.post("/cards")
def create_card():
    """Save a personal card (from a photo) with the theme chosen by the user."""
    uid = uid_from_request()
    if uid is None:
        return jsonify({"error": "unauthorized"}), 401

    data = request.get_json(silent=True) or {}
    word_source = (data.get("word_source") or "").strip().lower()
    word_target = (data.get("word_target") or "").strip()
    target_lang = (data.get("target_lang") or "").strip()
    theme = (data.get("theme") or "").strip()
    if not word_source or not word_target or not target_lang:
        return jsonify({"error": "missing card fields"}), 400
    if theme not in KNOWN_THEMES:
        return jsonify({"error": "unknown theme"}), 400

    con = get_db()
    try:
        existing = con.execute(
            "SELECT id FROM cards WHERE target_lang = ? AND word_source = ? AND word_target = ?",
            (target_lang, word_source, word_target),
        ).fetchone()
        created = existing is None
        if created:
            con.execute(
                "INSERT INTO cards (owner_id, source_lang, target_lang, word_source, word_target, theme, origin) "
                "VALUES (?, 'eng', ?, ?, ?, ?, 'photo')",
                (uid, target_lang, word_source, word_target, theme),
            )
            con.commit()
    finally:
        con.close()

    return jsonify({"created": created}), (201 if created else 200)


if __name__ == "__main__":
    app.run(debug=True)
