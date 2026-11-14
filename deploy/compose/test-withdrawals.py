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
        self.uploads_log = self.root / "uploads.jsonl"
        mock = '''#!/usr/bin/env python3
import gzip, json, os, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps([Path(sys.argv[0]).name] + args) + '\\n')
if Path(sys.argv[0]).name == 'aws':
    # `aws s3 cp SOURCE TARGET`: the source must already be complete when it is uploaded. Record what was read.
    source = Path(args[2])
    if not source.is_file():
        print(f'stub aws: {source} does not exist', file=sys.stderr)
        sys.exit(1)
    data = source.read_bytes()
    with open(os.environ['UPLOAD_LOG'], 'a') as log:
        log.write(json.dumps({'source': args[2], 'target': args[3], 'mode': oct(source.stat().st_mode & 0o777),
                              'content': (gzip.decompress(data) if source.suffix == '.gz' else data).decode()}) + '\\n')
    # UPLOAD_STATUS fails the withdrawal JOURNAL upload only; the dump's own upload is never failed here.
    sys.exit(int(os.environ.get('UPLOAD_STATUS', '0')) if 'withdrawals/' in args[3] else 0)
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
    host, container = args[args.index('-v') + 1].rsplit(':', 2)[:2]
    if '--entrypoint' in args:
        # The readability pre-check runs for real against the staged directory. UNREADABLE names a journal the
        # container cannot read; it is taken away before the check runs.
        if os.environ.get('UNREADABLE'):
            os.remove(Path(host) / os.environ['UNREADABLE'])
        command = [args[args.index('--entrypoint') + 1]] + args[args.index('census') + 1:]
        sys.exit(subprocess.run([arg.replace(container, host) for arg in command]).returncode)
    with open(os.environ['STAGED_LOG'], 'w') as out:
        json.dump({name: [oct(os.stat(Path(host) / name).st_mode & 0o777), (Path(host) / name).read_text()]
                   for name in ('withdrawals.csv', 'filter-floor.csv')}, out)
    sys.exit(int(os.environ.get('REPLAY_STATUS', '0')))
'''
        for name in ("docker", "aws"):
            script = self.bin / name
            script.write_text(mock)
            script.chmod(0o700)
        self.env = dict(
            os.environ, PATH=f"{self.bin}:{os.environ['PATH']}", COMMAND_LOG=str(self.log),
            STAGED_LOG=str(self.staged), UPLOAD_LOG=str(self.uploads_log), DUMP=DUMP,
        )
        self.env.pop("BACKUP_BUCKET", None)

    def run_script(self, name, *args, answer="RESTORE", **environment):
        return subprocess.run(
            [str(self.root / name), *map(str, args)], input=f"{answer}\n", text=True,
            capture_output=True, env=dict(self.env, **environment), check=False,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def uploads(self):
        return [json.loads(line) for line in self.uploads_log.read_text().splitlines()] if self.uploads_log.exists() else []

    def backup_steps(self):
        """The backup's commands in order, by what each one does."""
        def step(command):
            if command[0] == "aws":
                target = command[-1]
                return "upload " + ("withdrawals" if "/withdrawals/" in target else "floor" if "/filter-floor/" in target else "dump")
            if "pg_dump" in command:
                return "dump"
            if "FROM filter_floor" in command[-1]:
                return "export floor"
            if "COPY (SELECT install_id_hash" in command[-1]:
                return "export withdrawals"
            return " ".join(command)
        return [step(command) for command in self.commands()]

    def restore_steps(self):
        """The restore's commands in order, by what each one does."""
        def step(command):
            if "--entrypoint" in command:
                return "precheck"
            if command[1:] == ["compose", "stop", "census"]:
                return "stop"
            if any("FROM pg_class" in arg for arg in command):
                return "inspect"
            if "--single-transaction" in command:
                return "sql"
            if "run" in command and "--reapply-withdrawals" in command:
                return "replay"
            return " ".join(command)
        return [step(command) for command in self.commands()]

    def reset_logs(self):
        for log in (self.log, self.uploads_log, self.staged):
            log.unlink(missing_ok=True)

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
        # Each upload follows its own export and reads the finished, restricted file.
        self.assertEqual(
            ["dump", "upload dump", "export withdrawals", "upload withdrawals", "export floor", "upload floor"],
            self.backup_steps(),
        )
        uploads = self.uploads()
        [withdrawals] = (self.root / "backups/withdrawals").glob("*.csv")
        [floor] = (self.root / "backups/filter-floor").glob("*.csv")
        # The published files themselves, never a temporary, under their own names.
        self.assertEqual(
            [(f"backups/{dump.name}", f"s3://example/{dump.name}"),
             (f"backups/withdrawals/{withdrawals.name}", f"s3://example/withdrawals/{withdrawals.name}"),
             (f"backups/filter-floor/{floor.name}", f"s3://example/filter-floor/{floor.name}")],
            [(upload["source"], upload["target"]) for upload in uploads],
        )
        self.assertEqual(
            [written, "install_id_hash,withdrawn_at\n" + "a" * 64 + ",2026-10-03 12:00:00+00\n",
             "min_filter_rev,changed_day\n3,2026-10-03\n"],
            [upload["content"] for upload in uploads],
        )
        self.assertEqual(["0o600"] * 3, [upload["mode"] for upload in uploads])
        for journal in (withdrawals, floor):
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
        # Readability is proven before census stops; the database is inspected while census is stopped and before
        # any SQL; the replay runs last. Nothing else is run (no `up`).
        self.assertEqual(["precheck", "stop", "inspect", "sql", "replay"], self.restore_steps())
        commands = self.commands()
        # Astra review: the mount is a private STAGED copy (0600, owned by the invoker), never the operator's own file.
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
        self.assertFalse(Path(mount.rsplit(":", 2)[0]).exists())

    def test_restore_stops_at_an_unreadable_journal(self):
        dump, journal, floor = self.restore_inputs()
        for name in ("withdrawals.csv", "filter-floor.csv"):
            with self.subTest(unreadable=name):
                self.reset_logs()
                result = self.run_script("restore.sh", dump, journal, floor, UNREADABLE=name)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("recovery journals are not readable", result.stderr)
                self.assertEqual(["precheck"], self.restore_steps())
                self.assertFalse(self.staged.exists())

    def test_restore_failures_keep_census_stopped_and_run_nothing_after_them(self):
        dump, journal, floor = self.restore_inputs()
        result = self.run_script("restore.sh", dump, journal, floor, REPLAY_STATUS="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Restore complete", result.stdout)
        self.assertEqual(["precheck", "stop", "inspect", "sql", "replay"], self.restore_steps())
        self.reset_logs()
        result = self.run_script("restore.sh", dump, journal, floor, OBJECTS="4")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("census must be a fresh database", result.stderr)
        self.assertEqual(["precheck", "stop", "inspect"], self.restore_steps())
        self.reset_logs()
        result = self.run_script("restore.sh", dump, journal, floor, answer="no")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["precheck"], self.restore_steps())

if __name__ == "__main__":
    unittest.main()
