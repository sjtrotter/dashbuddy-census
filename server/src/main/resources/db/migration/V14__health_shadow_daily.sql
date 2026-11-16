-- #104: daily shadow-rule levels replace V13's 20-visit shadow counts (gig.log#1630). Forward-only.
--
-- No phone build ever sent V13's `shadow`, so every stored value is NULL (a per-install row) or '[]' (a fleet row);
-- the guard proves that on the database itself and refuses the migration otherwise, so dropping the columns loses
-- nothing. `shadow_daily` is the report's list of {generation, rule, digest, visits, evaluated, tied?, onUnknown?,
-- emptyParse?, maskFail?}: per candidate rule loaded on the phone that UTC day, zero visits included, `visits` and
-- `evaluated` as count levels ("0", "1-4", "5-19", "20+") and each metric as a fraction level (none, some,
-- half_or_more, all) of the visits where it could be measured, absent when there were none. The phone computes the
-- levels; no exact count is stored. `shadow_daily_truncated` is true when the phone held more candidates than it sent.
-- NULL means the report did not carry the field; a later report that omits it clears it. The columns live on the
-- per-install row, so its epoch pseudonym, 30-day retention, deletion and backups cover them. The fleet row keeps, per
-- (generation, rule, digest), the number of installs at each level of each field
-- ({"generation", "rule", "digest", "visits": {level: installs}, "evaluated": {...}, "<metric>": {...}}), and the
-- number of installs whose report was truncated. Levels are never summed or averaged, and different generations or
-- digests are never pooled. `probe_refused` is unchanged.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM health_daily WHERE shadow IS NOT NULL AND shadow <> '[]'::jsonb)
        OR EXISTS (SELECT 1 FROM health_fleet_daily WHERE shadow IS NOT NULL AND shadow <> '[]'::jsonb) THEN
        RAISE EXCEPTION 'V14 refuses: a legacy shadow column holds data';
    END IF;
END
$$;
ALTER TABLE health_daily DROP COLUMN shadow, ADD COLUMN shadow_daily jsonb NULL, ADD COLUMN shadow_daily_truncated boolean NULL;
ALTER TABLE health_fleet_daily DROP COLUMN shadow, ADD COLUMN shadow_daily jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN shadow_daily_truncated_installs int NOT NULL DEFAULT 0;
