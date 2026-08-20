"""Build polyglot.db from schema.sql and seed_cards.sql. Run locally."""

import pathlib
import sqlite3

DB_FILE = "polyglot.db"


def main():
    pathlib.Path(DB_FILE).unlink(missing_ok=True)

    con = sqlite3.connect(DB_FILE)
    con.execute("PRAGMA foreign_keys = ON;")
    con.executescript(pathlib.Path("schema.sql").read_text(encoding="utf-8"))

    # Drop MySQL-only lines; wrap in one transaction (per-INSERT commits are slow).
    seed = pathlib.Path("seed_cards.sql").read_text(encoding="utf-8")
    seed = "\n".join(l for l in seed.splitlines() if not l.strip().upper().startswith("SET "))
    con.executescript("BEGIN;\n" + seed + "\nCOMMIT;")
    con.commit()

    for table in ("users", "cards", "sessions", "errors"):
        print(f"{table:10}", con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
    con.close()


if __name__ == "__main__":
    main()
