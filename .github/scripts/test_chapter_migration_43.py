#!/usr/bin/env python3
"""Deterministic pre-migration-43 SQLite snapshot smoke test, not a device test."""
import sqlite3
from pathlib import Path


def main():
    migration = (
        Path(__file__).resolve().parents[2]
        / "data/src/main/sqldelight/tachiyomi/migrations/43.sqm"
    ).read_text(encoding="utf-8")
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys = ON")
    db.executescript("""
        CREATE TABLE tsuzuki_titles (id TEXT NOT NULL PRIMARY KEY);
        -- Unmodified pre-43 canonical chapter table from main.
        CREATE TABLE tsuzuki_canonical_chapters(
            id TEXT NOT NULL PRIMARY KEY,
            canonical_title_id TEXT NOT NULL,
            display_number TEXT NOT NULL,
            volume INTEGER,
            title TEXT,
            type TEXT NOT NULL,
            base_number INTEGER,
            part INTEGER,
            alpha_suffix TEXT,
            confidence REAL NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            confirmation_state TEXT NOT NULL DEFAULT 'PROVISIONAL',
            FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
        );
        CREATE TABLE tsuzuki_chapter_progress(
            canonical_chapter_id TEXT PRIMARY KEY REFERENCES tsuzuki_canonical_chapters(id),
            last_page_read INTEGER NOT NULL
        );
        CREATE TABLE tsuzuki_chapter_history(
            canonical_chapter_id TEXT PRIMARY KEY REFERENCES tsuzuki_canonical_chapters(id),
            last_read INTEGER NOT NULL
        );
        CREATE TABLE tsuzuki_canonical_downloads(
            id TEXT PRIMARY KEY,
            canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id),
            file_uri TEXT
        );
        CREATE TABLE tsuzuki_chapter_evidence(
            id TEXT PRIMARY KEY,
            mapped_canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id),
            external_chapter_key TEXT
        );
        CREATE TABLE tsuzuki_chapter_variants(
            id TEXT PRIMARY KEY,
            canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id),
            raw_name TEXT
        );
        INSERT INTO tsuzuki_titles VALUES ('title-1');
        INSERT INTO tsuzuki_canonical_chapters VALUES
            ('ch-05','title-1','12.05',2,'Fraction','REGULAR',12,5,NULL,0.9,111,222,'CONFIRMED'),
            ('ch-5','title-1','12.5',2,'Another','REGULAR',12,5,NULL,0.9,112,223,'CONFIRMED');
        INSERT INTO tsuzuki_chapter_progress VALUES ('ch-05',15);
        INSERT INTO tsuzuki_chapter_history VALUES ('ch-05',999);
        INSERT INTO tsuzuki_canonical_downloads VALUES ('dl','ch-05','file:///chapter.cbz');
        INSERT INTO tsuzuki_chapter_evidence VALUES ('evidence','ch-05','/chapter/12.05');
        INSERT INTO tsuzuki_chapter_variants VALUES ('variant','ch-05','Chapter 12.05');
    """)
    tables = (
        "tsuzuki_canonical_chapters", "tsuzuki_chapter_progress",
        "tsuzuki_chapter_history", "tsuzuki_canonical_downloads",
        "tsuzuki_chapter_evidence", "tsuzuki_chapter_variants",
    )
    before = {table: db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall() for table in tables}
    db.executescript(migration)
    after = {table: db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall() for table in tables}
    assert [row[:13] for row in after["tsuzuki_canonical_chapters"]] == before["tsuzuki_canonical_chapters"]
    assert all(row[13:] == (None, None, None) for row in after["tsuzuki_canonical_chapters"])
    for table in tables[1:]:
        assert after[table] == before[table], table
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.execute(
        "UPDATE tsuzuki_canonical_chapters SET numbering_kind='DECIMAL',"
        "decimal_numerator=5, decimal_scale=2 WHERE id='ch-05'"
    )
    assert db.execute("SELECT numbering_kind FROM tsuzuki_canonical_chapters WHERE id='ch-5'").fetchone() == (None,)
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    print("PASS: pre-43 IDs, progress, history, downloads, evidence and variants preserved")


if __name__ == "__main__":
    main()
