"""Offline script regression checks: python3 deploy/compose/test-withdrawals.py."""
import datetime
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class WithdrawalScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in ("backup.sh", "restore.sh", "export-withdrawals.sh"):
            shutil.copy2(Path(__file__).with_name(name), self.root)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "commands.jsonl"
        mock = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps([Path(sys.argv[0]).name] + args) + '\\n')
if any('COPY (' in arg for arg in args):
    if os.environ.get('FAIL_EXPORT') == '1':
        print('partial output')
        sys.exit(1)
    print('install_id_hash,withdrawn_at')
    print('a' * 64 + ',2026-10-03 12:00:00+00')
elif 'pg_dump' in args:
    print('SELECT 1;')
elif any('SELECT count(*) FROM pg_class' in arg for arg in args):
    print('0')
elif '--single-transaction' in args:
    sys.stdin.read()
elif 'run' in args:
    sys.exit(int(os.environ.get('REPLAY_STATUS', '0')))
elif Path(sys.argv[0]).name == 'aws':
    sys.exit(int(os.environ.get('UPLOAD_STATUS', '0')))
'''
        for name in ("docker", "aws"):
            script = self.bin / name
            script.write_text(mock)
            script.chmod(0o700)
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}", COMMAND_LOG=str(self.log))
        self.env.pop("BACKUP_BUCKET", None)

    def run_script(self, name, *args, **environment):
        return subprocess.run(
            [str(self.root / name), *map(str, args)], input="RESTORE\n", text=True,
            capture_output=True, env=dict(self.env, **environment), check=False,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def restore_inputs(self):
        dump = self.root / "backup.sql.gz"
        with gzip.open(dump, "wt") as stream:
            stream.write("SELECT 1;\n")
        journal = self.root / "private journal.csv"
        journal.write_text("install_id_hash,withdrawn_at\n")
        journal.chmod(0o600)
        return dump, journal

    def test_backup_exports_and_uploads_journal_before_dump(self):
        result = self.run_script("backup.sh", BACKUP_BUCKET="s3://example")
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertIn("COPY (SELECT install_id_hash", commands[0][-1])
        self.assertEqual("aws", commands[1][0])
        self.assertTrue(commands[1][-1].startswith("s3://example/withdrawals/"))
        self.assertIn("pg_dump", commands[2])
        journal = next((self.root / "backups/withdrawals").glob("*.csv"))
        self.assertEqual(0o600, journal.stat().st_mode & 0o777)
        self.assertIn("(1 rows)", result.stdout)

    def test_export_failure_aborts_backup_without_publishing_partial_file(self):
        result = self.run_script("backup.sh", FAIL_EXPORT="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.commands()))
        self.assertEqual([], list((self.root / "backups/withdrawals").iterdir()))

    def test_journal_upload_failure_aborts_before_dump(self):
        result = self.run_script("backup.sh", BACKUP_BUCKET="example", UPLOAD_STATUS="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("pg_dump" in command for command in self.commands()))

    def test_export_retains_fourteen_utc_dates(self):
        directory = self.root / "backups/withdrawals"
        directory.mkdir(parents=True)
        today = datetime.datetime.now(datetime.timezone.utc).date()
        paths = []
        for age in (13, 14):
            path = directory / f"withdrawals-{today - datetime.timedelta(days=age):%Y%m%d}T000000Z.csv"
            path.touch()
            paths.append(path)
        result = self.run_script("export-withdrawals.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(paths[0].exists())
        self.assertFalse(paths[1].exists())

    def test_restore_refuses_missing_journal_and_replays_after_sql(self):
        dump, journal = self.restore_inputs()
        self.assertNotEqual(0, self.run_script("restore.sh", dump).returncode)
        self.assertEqual([], self.commands())
        result = self.run_script("restore.sh", dump, journal)
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertIn("--single-transaction", commands[-2])
        self.assertIn(f"{journal}:/journal.csv:ro", commands[-1])
        self.assertEqual(["census", "--reapply-withdrawals", "/journal.csv"], commands[-1][-3:])
        self.assertIn("Withdrawals replayed", result.stdout)
        self.assertFalse(any("up" in command for command in commands))

    def test_restore_replay_failure_is_fatal_and_no_journal_is_explicit(self):
        dump, journal = self.restore_inputs()
        result = self.run_script("restore.sh", dump, journal, REPLAY_STATUS="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Restore complete", result.stdout)
        result = self.run_script("restore.sh", dump, "--no-journal")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Withdrawals after the dump are NOT covered", result.stderr)
        self.assertEqual(["--reapply-withdrawals", "/dev/null"], self.commands()[-1][-2:])


if __name__ == "__main__":
    unittest.main()
