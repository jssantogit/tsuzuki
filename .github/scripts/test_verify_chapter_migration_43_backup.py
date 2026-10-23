#!/usr/bin/env python3
"""Small regression suite for the offline historical-backup acceptance helper."""
from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from verify_chapter_migration_43_backup import VerificationError, verify_backup

MIGRATION = Path(__file__).resolve().parents[2] / "data/src/main/sqldelight/tachiyomi/migrations/43.sqm"


class HistoricalBackupVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tsuzuki-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.backup = Path(self.temp.name) / "historic.sqlite"
        with sqlite3.connect(self.backup) as db:
            db.executescript("""
                PRAGMA foreign_keys=ON;
                CREATE TABLE tsuzuki_titles(id TEXT PRIMARY KEY);
                CREATE TABLE tsuzuki_canonical_chapters (
                    id TEXT NOT NULL PRIMARY KEY,
                    canonical_title_id TEXT NOT NULL REFERENCES tsuzuki_titles(id),
                    display_number TEXT NOT NULL, volume INTEGER, title TEXT,
                    type TEXT NOT NULL, base_number INTEGER, part INTEGER,
                    alpha_suffix TEXT, confidence REAL NOT NULL, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    confirmation_state TEXT NOT NULL DEFAULT 'PROVISIONAL'
                );
                CREATE TABLE tsuzuki_chapter_progress (
                    canonical_chapter_id TEXT PRIMARY KEY REFERENCES tsuzuki_canonical_chapters(id),
                    last_page_read INTEGER NOT NULL
                );
                CREATE TABLE tsuzuki_chapter_history (
                    canonical_chapter_id TEXT PRIMARY KEY REFERENCES tsuzuki_canonical_chapters(id),
                    last_read INTEGER NOT NULL
                );
                CREATE TABLE tsuzuki_canonical_downloads (
                    id TEXT PRIMARY KEY, canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id)
                );
                CREATE TABLE tsuzuki_chapter_evidence (
                    id TEXT PRIMARY KEY, mapped_canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id)
                );
                CREATE TABLE tsuzuki_chapter_variants (
                    id TEXT PRIMARY KEY, canonical_chapter_id TEXT REFERENCES tsuzuki_canonical_chapters(id)
                );
                CREATE TABLE unrelated_binary (id INTEGER PRIMARY KEY, payload BLOB);
                INSERT INTO tsuzuki_titles VALUES ('title');
                INSERT INTO tsuzuki_canonical_chapters VALUES
                  ('chapter05','title','12.05',2,'Extra','REGULAR',12,5,NULL,0.9,1,2,'CONFIRMED'),
                  ('chapter5','title','12.5',2,'Extra','REGULAR',12,5,NULL,0.9,2,3,'CONFIRMED');
                INSERT INTO tsuzuki_chapter_progress VALUES ('chapter05',7);
                INSERT INTO tsuzuki_chapter_history VALUES ('chapter05',20);
                INSERT INTO tsuzuki_canonical_downloads VALUES ('download','chapter05');
                INSERT INTO tsuzuki_chapter_evidence VALUES ('evidence','chapter05');
                INSERT INTO tsuzuki_chapter_variants VALUES ('variant','chapter05');
                INSERT INTO unrelated_binary VALUES (1, X'00FF8000');
            """)

    def test_success_preserves_input_bytes_and_related_rows(self):
        original = self.backup.read_bytes()
        result = verify_backup(self.backup, MIGRATION)
        self.assertEqual(result['chapters_checked'], 2)
        self.assertEqual(result['tables_checked'], 8)
        self.assertEqual(self.backup.read_bytes(), original)
        with sqlite3.connect(self.backup) as db:
            self.assertEqual(len(db.execute('PRAGMA table_info(tsuzuki_canonical_chapters)').fetchall()), 13)
            self.assertEqual(db.execute('SELECT last_page_read FROM tsuzuki_chapter_progress').fetchone(), (7,))

    def test_preexisting_v2_columns_fail_closed(self):
        with sqlite3.connect(self.backup) as db:
            db.execute('ALTER TABLE tsuzuki_canonical_chapters ADD COLUMN numbering_kind TEXT')
        with self.assertRaisesRegex(VerificationError, 'already has v2'):
            verify_backup(self.backup, MIGRATION)

    def test_existing_foreign_key_violation_is_not_accepted(self):
        with sqlite3.connect(self.backup) as db:
            db.execute('PRAGMA foreign_keys=OFF')
            db.execute("INSERT INTO tsuzuki_chapter_progress VALUES ('missing',99)")
        with self.assertRaisesRegex(VerificationError, 'foreign-key'):
            verify_backup(self.backup, MIGRATION)

    def test_mutating_migration_is_rejected_without_touching_original(self):
        dangerous = Path(self.temp.name) / 'mutating.sqm'
        dangerous.write_text(MIGRATION.read_text() + "\nDELETE FROM tsuzuki_chapter_progress;\n")
        original = self.backup.read_bytes()
        with self.assertRaisesRegex(VerificationError, 'changed existing database records'):
            verify_backup(self.backup, dangerous)
        self.assertEqual(self.backup.read_bytes(), original)

    def test_renaming_unrelated_table_is_rejected(self):
        dangerous = Path(self.temp.name) / 'rename.sqm'
        dangerous.write_text(MIGRATION.read_text() + "\nALTER TABLE unrelated_binary RENAME TO deleted_name;\n")
        with self.assertRaisesRegex(VerificationError, 'added or removed'):
            verify_backup(self.backup, dangerous)


if __name__ == '__main__':
    unittest.main()
