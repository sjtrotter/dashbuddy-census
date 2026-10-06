-- #1192: hashed withdrawal tombstones. Survives the install's deletion; replayed at startup and after a restore.
CREATE TABLE withdrawals (
    install_id_hash text PRIMARY KEY,          -- lower-hex sha256 of the install_id UUID's canonical string
    withdrawn_at    timestamptz NOT NULL
);
CREATE INDEX withdrawals_withdrawn_at_idx ON withdrawals (withdrawn_at);

-- V1 retained only created_day. Preserve that lower bound for legacy generations;
-- new enrolments need an instant so a same-day re-enrolment survives replay.
ALTER TABLE installs ADD COLUMN enrolled_at timestamptz;
UPDATE installs SET enrolled_at = created_day::timestamp AT TIME ZONE 'UTC';
ALTER TABLE installs ALTER COLUMN enrolled_at SET NOT NULL;
ALTER TABLE installs ALTER COLUMN enrolled_at SET DEFAULT CURRENT_TIMESTAMP;
