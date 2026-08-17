"""
build_db.py
-----------
Costruisce il database SQLite `polyglot.db` in LOCALE, partendo da:
  - schema.sql       (le tabelle)
  - seed_cards.sql   (le carte generate da extract_omw.py)

Poi carichi il singolo file `polyglot.db` su PythonAnywhere (tab Files) e Flask
lo apre con il modulo `sqlite3` (nessun server, nessuna credenziale).

Uso:
    python build_db.py
"""

import sqlite3
import pathlib

DB_FILE = "polyglot.db"


def main():
    pathlib.Path(DB_FILE).unlink(missing_ok=True)  # riparte pulito

    con = sqlite3.connect(DB_FILE)
    con.execute("PRAGMA foreign_keys = ON;")

    # 1) schema
    con.executescript(pathlib.Path("schema.sql").read_text(encoding="utf-8"))

    # 2) carte: rimuovi le righe MySQL-only (es. "SET NAMES utf8mb4;")
    seed = pathlib.Path("seed_cards.sql").read_text(encoding="utf-8")
    seed = "\n".join(
        line for line in seed.splitlines()
        if not line.strip().upper().startswith("SET ")
    )
    # Un'unica transazione: senza BEGIN/COMMIT SQLite committerebbe su disco a
    # ogni INSERT (~30k) rendendo il caricamento lentissimo.
    con.executescript("BEGIN;\n" + seed + "\nCOMMIT;")
    con.commit()

    # 3) riepilogo
    for table in ("users", "cards", "sessions", "attempts"):
        n = con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"{table:10} {n}")
    print("\ncarte per lingua:")
    for lang, n in con.execute(
        "SELECT target_lang, COUNT(*) FROM cards GROUP BY target_lang ORDER BY 2 DESC"
    ):
        print(f"  {lang}  {n}")

    con.close()
    size_mb = pathlib.Path(DB_FILE).stat().st_size / (1024 * 1024)
    print(f"\nCreato {DB_FILE} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
