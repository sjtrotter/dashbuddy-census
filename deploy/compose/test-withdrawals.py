"""Offline script regression checks: python3 -I deploy/compose/test-withdrawals.py (CI runs it).

Stubs `docker` and `aws` on PATH and drives the real backup.sh, restore.sh and export scripts against them.
"""
import datetime
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = (
    "backup.sh", "restore.sh", "export-withdrawals.sh", "export-filter-floor.sh", "order-tagged-dump.sh",
)

# The stub's pg_dump: one epoch-tagged COPY block whose rows are out of tag order, and one untagged block.
DUMP = (
    "SELECT 1;\n"
    "COPY public.token_sightings (id, install_tag, token) FROM stdin;\n"
    "1\tcc\tx\n2\taa\ty\n3\tbb\tz\n"
    "\\.\n"
    "COPY public.policy (id, value) FROM stdin;\n"
    "2\tb\n1\ta\n"
    "\\.\n"
)


class WithdrawalScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in SCRIPTS:
            shutil.copy2(Path(__file__).with_name(name), self.root)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "commands.jsonl"
        self.staged = self.root / "staged.json"
        mock = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps([Path(sys.argv[0]).name] + args) + '\\n')
if any('FROM filter_floor' in arg for arg in args):
    if os.environ.get('FAIL_FLOOR') == '1':
        sys.exit(1)
    print('min_filter_rev,changed_day')
    print('3,2026-10-03')
elif any('COPY (' in arg for arg in args):
    if os.environ.get('FAIL_EXPORT') == '1':
        print('partial output')
        sys.exit(1)
    print('install_id_hash,withdrawn_at')
    print('a' * 64 + ',2026-10-03 12:00:00+00')
elif 'pg_dump' in args:
    sys.stdout.write(os.environ['DUMP'])
elif any('SELECT count(*) FROM pg_class' in arg for arg in args):
    print(os.environ.get('OBJECTS', '0'))
elif '--single-transaction' in args:
    sys.stdin.read()
elif 'run' in args:
    # The restore pre-check (`--entrypoint /bin/sh … test -r`) always passes in the stub; REPLAY_STATUS drives the replay.
    if '--entrypoint' in args:
        sys.exit(0)
    staged = Path(args[args.index('-v') + 1].rsplit(':', 2)[0])
    with open(os.environ['STAGED_LOG'], 'w') as out:
        json.dump({name: [oct(os.stat(staged / name).st_mode & 0o777), (staged / name).read_text()]
                   for name in ('withdrawals.csv', 'filter-floor.csv')}, out)
    sys.exit(int(os.environ.get('REPLAY_STATUS', '0')))
elif Path(sys.argv[0]).name == 'aws':
    # UPLOAD_STATUS fails the withdrawal JOURNAL upload only; the dump's own upload is never failed here.
    sys.exit(int(os.environ.get('UPLOAD_STATUS', '0')) if any('withdrawals/' in arg for arg in args) else 0)
'''
        for name in ("docker", "aws"):
            script = self.bin / name
            script.write_text(mock)
            script.chmod(0o700)
        self.env = dict(
            os.environ, PATH=f"{self.bin}:{os.environ['PATH']}", COMMAND_LOG=str(self.log),
            STAGED_LOG=str(self.staged), DUMP=DUMP,
        )
        self.env.pop("BACKUP_BUCKET", None)

    def run_script(self, name, *args, **environment):
        return subprocess.run(
            [str(self.root / name), *map(str, args)], input="RESTORE\n", text=True,
            capture_output=True, env=dict(self.env, **environment), check=False,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def dumps(self):
        return list((self.root / "backups").glob("census-*.sql.gz"))

    def restore_inputs(self):
        dump = self.root / "backup.sql.gz"
        with gzip.open(dump, "wt") as stream:
            stream.write("SELECT 1;\n")
        journal = self.root / "private journal.csv"
        journal.write_text("install_id_hash,withdrawn_at\n")
        journal.chmod(0o644)
        floor = self.root / "private floor.csv"
        floor.write_text("min_filter_rev,changed_day\n3,2026-10-03\n")
        floor.chmod(0o644)
        return dump, journal, floor

    def test_backup_dumps_in_tag_order_then_exports_and_uploads_both_journals(self):
        # #1192 (fable review F3): the dump already holds the tombstones as of dump time; the journal exports
        # run AFTER it so an export/upload failure can never cost the day's dump.
        result = self.run_script("backup.sh", BACKUP_BUCKET="s3://example")
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertIn("pg_dump", commands[0])
        # #81: the quarantine and the day's slot release are dumped empty.
        for table in ("quarantine_items", "slot_release", "slot_release_days"):
            self.assertIn(f"--exclude-table-data={table}", commands[0])
        # #80: epoch-tagged rows leave in tag order; every other block keeps its dump order.
        [dump] = self.dumps()
        with gzip.open(dump, "rt") as stream:
            written = stream.read()
        self.assertEqual(DUMP.replace("1\tcc\tx\n2\taa\ty\n3\tbb\tz\n", "2\taa\ty\n3\tbb\tz\n1\tcc\tx\n"), written)
        self.assertEqual(0o600, dump.stat().st_mode & 0o777)
        uploads = [command[-1] for command in commands if command[0] == "aws"]
        self.assertEqual(f"s3://example/{dump.name}", uploads[0])
        self.assertTrue(uploads[1].startswith("s3://example/withdrawals/"), uploads)
        self.assertTrue(uploads[2].startswith("s3://example/filter-floor/"), uploads)
        withdrawals = next(i for i, command in enumerate(commands) if "COPY (SELECT install_id_hash" in command[-1])
        floor = next(i for i, command in enumerate(commands) if "FROM filter_floor" in command[-1])
        self.assertLess(0, withdrawals)
        self.assertLess(withdrawals, floor)
        for directory in ("withdrawals", "filter-floor"):
            [journal] = (self.root / "backups" / directory).glob("*.csv")
            self.assertEqual(0o600, journal.stat().st_mode & 0o777)
        self.assertIn("(1 rows)", result.stdout)
        self.assertIn("Backup recovery journals exported successfully.", result.stdout)

    def test_withdrawal_export_failure_fails_the_backup_and_keeps_the_dump(self):
        result = self.run_script("backup.sh", FAIL_EXPORT="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("exported successfully", result.stdout)
        self.assertEqual(1, len(self.dumps()))
        self.assertEqual([], list((self.root / "backups/withdrawals").iterdir()))
        self.assertFalse(any("FROM filter_floor" in command[-1] for command in self.commands()))

    def test_journal_upload_or_floor_export_failure_fails_the_backup_and_keeps_the_dump(self):
        for environment in ({"BACKUP_BUCKET": "example", "UPLOAD_STATUS": "1"}, {"FAIL_FLOOR": "1"}):
            with self.subTest(environment=environment):
                result = self.run_script("backup.sh", **environment)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("exported successfully", result.stdout)
                self.assertEqual(1, len(self.dumps()))

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

    def test_restore_refuses_a_missing_journal_and_replays_both_after_sql(self):
        dump, journal, floor = self.restore_inputs()
        for arguments in ((dump,), (dump, journal), (dump, journal, self.root / "absent.csv")):
            with self.subTest(arguments=arguments):
                self.assertNotEqual(0, self.run_script("restore.sh", *arguments).returncode)
                self.assertEqual([], self.commands())
        result = self.run_script("restore.sh", dump, journal, floor)
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        # Astra review: readability is PROVEN before any SQL — the pre-check precedes the restore transaction,
        # and the mount is a private STAGED copy (0600, owned by the invoker), never the operator's own file.
        precheck = next(i for i, command in enumerate(commands) if "--entrypoint" in command)
        restore = next(i for i, command in enumerate(commands) if "--single-transaction" in command)
        self.assertLess(precheck, restore)
        self.assertIn("--single-transaction", commands[-2])
        mount = commands[-1][commands[-1].index("-v") + 1]
        self.assertTrue(mount.endswith(":/recovery:ro") and "census-recovery." in mount, mount)
        self.assertFalse(any(str(journal) in arg or str(floor) in arg for command in commands for arg in command))
        self.assertEqual(
            ["census", "--reapply-withdrawals", "/recovery/withdrawals.csv", "--filter-floor", "/recovery/filter-floor.csv"],
            commands[-1][-5:],
        )
        staged = json.loads(self.staged.read_text())
        self.assertEqual(["0o600", journal.read_text()], staged["withdrawals.csv"])
        self.assertEqual(["0o600", floor.read_text()], staged["filter-floor.csv"])
        self.assertIn("Restore complete", result.stdout)
        self.assertFalse(any("up" in command for command in commands))
        self.assertFalse(Path(mount.rsplit(":", 2)[0]).exists())

    def test_restore_replay_failure_is_fatal_and_a_used_database_is_refused(self):
        dump, journal, floor = self.restore_inputs()
        result = self.run_script("restore.sh", dump, journal, floor, REPLAY_STATUS="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Restore complete", result.stdout)
        self.log.unlink()
        result = self.run_script("restore.sh", dump, journal, floor, OBJECTS="4")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("census must be a fresh database", result.stderr)
        self.assertFalse(any("--single-transaction" in command for command in self.commands()))


if __name__ == "__main__":
    unittest.main()
