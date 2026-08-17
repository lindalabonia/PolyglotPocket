"""
app.py - Backend REST di Polyglot Pocket (versione minima per il primo test).

Endpoint (tutti in sola lettura, senza autenticazione - l'auth Google la
aggiungiamo dopo):
    GET /            -> {"status": "ok"}          (test connettivita')
    GET /ping        -> {"status": "ok"}
    GET /languages   -> ["spa","fra","por","nld","jpn"]
    GET /cards?target_lang=spa&n=10 -> [ {id, word_source, word_target, theme}, ... ]

Su PythonAnywhere: metti polyglot.db nella STESSA cartella di questo file.
"""

import os
import sqlite3

from flask import Flask, jsonify, request

app = Flask(__name__)

# polyglot.db accanto a questo file (funziona sia in locale sia su PA).
DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "polyglot.db")

MAX_CARDS = 100  # limite di sicurezza su n


def get_db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row  # cosi' possiamo leggere le colonne per nome
    return con


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
        return jsonify({"error": "parametro 'target_lang' mancante"}), 400

    # n con default 10 e clamp tra 1 e MAX_CARDS
    try:
        n = int(request.args.get("n", 10))
    except ValueError:
        return jsonify({"error": "parametro 'n' non valido"}), 400
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


if __name__ == "__main__":
    # Esecuzione in LOCALE per provare prima di caricare su PA:
    #   python app.py   ->   http://127.0.0.1:5000/cards?target_lang=spa&n=1
    app.run(debug=True)
