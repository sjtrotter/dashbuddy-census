-- #102: shadow-rule counts in the health report (gig.log#1630). Forward-only.
--
-- `shadow` is the report's list of {generation, rule, digest, tied?, onUnknown?, emptyParse?, maskFail?}: per candidate
-- rule, each metric counts hits among its last 20 eligible visits of the day, and an unavailable metric is absent.
-- `probe_refused` is the list of {generation, rule, digest} the phone refused to evaluate. Ids and counts only, never
-- screen text. NULL means the report did not carry the field; a later report that omits it clears it. The columns live
-- on the per-install row, so its epoch pseudonym, 30-day retention, deletion and backups cover them. The fleet row keeps,
-- per (generation, rule, digest, metric), the summed hits and the number of installs that reported the metric
-- ({"generation", "rule", "digest", "metric", "total", "installs"}), and per refused probe its install count, after the
-- per-install rows are folded and deleted. Different generations or digests are never pooled.
ALTER TABLE health_daily ADD COLUMN shadow jsonb NULL, ADD COLUMN probe_refused jsonb NULL;
ALTER TABLE health_fleet_daily ADD COLUMN shadow jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN probe_refused jsonb NOT NULL DEFAULT '[]'::jsonb;
