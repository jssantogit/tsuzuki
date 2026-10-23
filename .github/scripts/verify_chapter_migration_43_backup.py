#!/usr/bin/env python3
"""Read-only, offline check of migration 43 against an existing SQLite backup.

Never runs against the supplied database: sqlite3.backup copies its consistent
snapshot to a disposable private working database first. No content is printed.
"""
from __future__ import annotations

import argparse
import hashlib
import pickle
import sqlite3
import tempfile
from pathlib import Path

MIGRATION = Path(__file__).resolve().parents[2] / "data/src/main/sqldelight/tachiyomi/migrations/43.sqm"
ADDED = ("numbering_kind", "decimal_numerator", "decimal_scale")
CHAPTERS = "tsuzuki_canonical_chapters"


class VerificationError(Exception):
    pass


def quote_identifier(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def tables(db: sqlite3.Connection) -> dict[str, tuple[str, ...]]:
    names = [name for (name,) in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )]
    return {
        name: tuple(info[1] for info in db.execute(f"PRAGMA table_info({quote_identifier(name)})"))
        for name in names
    }


def fingerprint(db: sqlite3.Connection, table: str, columns: tuple[str, ...]) -> tuple[int, str]:
    # Snapshot and post-migration scans have identical row ordering: ALTER TABLE
    # only adds nullable columns, without changing records or indexes.
    h = hashlib.sha256()
    count = 0
    selected = ", ".join(map(quote_identifier, columns))
    for row in db.execute(f"SELECT {selected} FROM {quote_identifier(table)}"):
        encoded = pickle.dumps(row, protocol=4)
        h.update(len(encoded).to_bytes(8, 'big'))
        h.update(encoded)
        count += 1
    return count, h.hexdigest()


def verify_backup(backup_path: Path, migration_path: Path = MIGRATION) -> dict[str, int]:
    if not backup_path.is_file():
        raise VerificationError("Backup not found or not a regular file")
    if not migration_path.is_file():
        raise VerificationError("Migration 43 was not found")
    if any(backup_path.with_name(backup_path.name + suffix).exists() for suffix in ("-wal", "-shm")):
        raise VerificationError("Supply a checkpointed offline SQLite snapshot, not a live WAL database")
    with tempfile.TemporaryDirectory(prefix="tsuzuki-chapter-43-") as directory:
        with sqlite3.connect(backup_path.resolve().as_uri() + "?mode=ro&immutable=1", uri=True) as source:
            # The only writable connection is the disposable local copy.
            with sqlite3.connect(str(Path(directory) / "working.sqlite")) as db:
                source.backup(db)
                old_tables = tables(db)
                old_cols = old_tables.get(CHAPTERS)
                if not old_cols or len(old_cols) < 13:
                    raise VerificationError("Canonical chapter table missing or incompatible")
                if any(col in old_cols for col in ADDED):
                    raise VerificationError("Backup already has v2 numbering columns")
                if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise VerificationError("Backup failed SQLite integrity check")
                if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
                    raise VerificationError("Backup contains pre-existing foreign-key violations")
                before = {
                    name: fingerprint(db, name, columns)
                    for name, columns in old_tables.items()
                    if columns  # Some virtual/shadow tables have no visible columns.
                }
                try:
                    db.executescript(migration_path.read_text(encoding="utf-8"))
                except sqlite3.Error as error:
                    raise VerificationError("Migration 43 SQL failed on isolated copy") from error
                new_tables = tables(db)
                if set(new_tables) != set(old_tables):
                    raise VerificationError("Migration unexpectedly added or removed tables")
                if new_tables[CHAPTERS] != old_cols + ADDED:
                    raise VerificationError("Migration did not append exactly the expected columns")
                if any(new_tables[name] != cols for name, cols in old_tables.items() if name != CHAPTERS):
                    raise VerificationError("Migration altered unrelated table columns")
                for name, cols in old_tables.items():
                    if cols and fingerprint(db, name, cols) != before[name]:
                        raise VerificationError("Migration changed existing database records")
                if db.execute(
                    f"SELECT 1 FROM {quote_identifier(CHAPTERS)} "
                    "WHERE numbering_kind IS NOT NULL OR decimal_numerator IS NOT NULL "
                    "OR decimal_scale IS NOT NULL LIMIT 1"
                ).fetchone():
                    raise VerificationError("Migration populated v2 metadata on historical rows")
                if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise VerificationError("Migrated copy failed SQLite integrity check")
                if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
                    raise VerificationError("Migrated copy failed foreign-key checks")
                return {"tables_checked": len(old_tables), "chapters_checked": before[CHAPTERS][0]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backup", type=Path, help="Existing offline SQLite database snapshot (never modified)")
    args = parser.parse_args()
    try:
        report = verify_backup(args.backup)
    except (VerificationError, sqlite3.Error, OSError) as error:
        # Never print database values, paths or SQL driver errors from private user data.
        print(f"FAIL: {type(error).__name__}; migration 43 not accepted")
        return 1
    print(f"PASS: migration 43 preserved {report['chapters_checked']} canonical chapters "
          f"across {report['tables_checked']} tables (isolated copy only)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
