-- #79: keep less, unlinked (council round 3). Forward-only.

-- Cluster sightings record presence only: one row per (cluster, install, observation day, version, domain,
-- revision), never a per-install count. The column stays, pinned to 1, because startup re-validates V5's table
-- shape against the legacy table. Retention drops from 90 to 30 days in Policy, not here.
UPDATE cluster_sightings_v5 SET count = 1 WHERE count <> 1;
ALTER TABLE cluster_sightings_v5 ADD CONSTRAINT cluster_sightings_v5_presence CHECK (count = 1);

-- The enrolment instant becomes a day: installs.created_day already holds the UTC enrolment day.
-- erased_through is the newest deletion tombstone instant (withdrawals.withdrawn_at) this credential generation is
-- known to postdate or to have already applied. Withdrawal replay erases a generation unless erased_through is at
-- least the tombstone's instant, so a same-day re-enrolment survives replay without storing when anyone enrolled.
ALTER TABLE installs ADD COLUMN erased_through timestamptz NULL;
UPDATE installs i SET erased_through = w.withdrawn_at FROM withdrawals w
    WHERE w.install_id_hash = encode(sha256(i.install_id::text::bytea), 'hex') AND i.enrolled_at > w.withdrawn_at;
ALTER TABLE installs DROP COLUMN enrolled_at;

-- Per-install health-report quota: accepted reports per install per UTC receipt day, beside the skeleton counters.
ALTER TABLE ingest_ledger ADD COLUMN health_reports integer NOT NULL DEFAULT 0;

-- The upload pause switch: one row, set by the operator; true refuses uploads from every non-trusted install.
CREATE TABLE ingest_pause (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    paused boolean NOT NULL,
    changed_day date NOT NULL
);

-- Vocabulary provenance names no install (Sol S2): `trusted:<install prefix>` becomes plain `trusted`, and the
-- prefix form is refused from now on, so a reviewed token never keeps a link to the install that showed it.
UPDATE vocabulary_v5 SET source = 'trusted' WHERE source LIKE 'trusted:%';
UPDATE vocabulary SET source = 'trusted' WHERE source LIKE 'trusted:%';
ALTER TABLE vocabulary_v5 ADD CONSTRAINT vocabulary_v5_source_unlinked CHECK (source IS NULL OR source NOT LIKE 'trusted:%');
