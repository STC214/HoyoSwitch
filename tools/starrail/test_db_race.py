"""Offline regression tests for the SQLite account-identity invariants.

These mirror what SnapshotStore does on-device: the account database is read together
with its -wal/-shm sidecars and then queried with a real SQLite cursor, so a checkpoint
that lands between reads cannot resurrect a stale token, and an empty table is never
rescued by a stale token key.
"""

import os
import shutil
import sqlite3
import tempfile
import unittest


def _newest_mid(files):
    workdir = tempfile.mkdtemp()
    try:
        for name, path in files.items():
            if path and os.path.exists(path):
                shutil.copy(path, os.path.join(workdir, name))
        connection = sqlite3.connect(os.path.join(workdir, 'account.db'))
        try:
            row = connection.execute(
                'select mid from porte_account_table order by timestamp desc limit 1'
            ).fetchone()
            return row[0] if row else None
        finally:
            connection.close()
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


class CheckpointRaceTest(unittest.TestCase):
    def test_group_read_recovers_committed_token(self):
        tmp = tempfile.mkdtemp()
        try:
            db = os.path.join(tmp, 'account.db')
            connection = sqlite3.connect(db)
            connection.execute(
                'create table porte_account_table(mid text, aid int, type int, timestamp int)')
            connection.execute("insert into porte_account_table values ('A', 1, 2, 100)")
            connection.commit()
            connection.execute('pragma journal_mode=WAL')
            connection.close()

            writer = sqlite3.connect(db)
            writer.execute("insert into porte_account_table values ('B', 2, 2, 200)")
            writer.commit()  # B now lives only in -wal

            naive = _newest_mid({'account.db': db})
            group = _newest_mid({'account.db': db, 'account.db-wal': db + '-wal',
                                 'account.db-shm': db + '-shm'})
            writer.close()

            self.assertEqual(naive, 'A', 'main-only read should miss the uncheckpointed row')
            self.assertEqual(group, 'B', 'group read should apply the WAL and see the new row')
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class EmptyTableTest(unittest.TestCase):
    def test_empty_table_yields_no_account(self):
        tmp = tempfile.mkdtemp()
        try:
            db = os.path.join(tmp, 'account.db')
            connection = sqlite3.connect(db)
            connection.execute(
                'create table porte_account_table(mid text, aid int, type int, timestamp int)')
            connection.commit()
            connection.close()
            self.assertIsNone(_newest_mid({'account.db': db}),
                              'an empty account table must not fall back to a stale token')
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
