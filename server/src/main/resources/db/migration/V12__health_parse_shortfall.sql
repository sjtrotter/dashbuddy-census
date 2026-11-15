-- #64: per-rule parse-shortfall counts in the health report. Forward-only.
--
-- A report may carry, per rule id, how many of the rule's eligible matched frames left a required field null:
-- {"<rule id>": {"shortfall": n, "eligible": m}}. Rule ids and counts only, never screen text. NULL means the report
-- did not carry the counts (an older phone). The per-install row keeps the health table's 30-day retention; the fleet
-- row sums each rule's two counts across installs and keeps them after the per-install rows are folded and deleted.
ALTER TABLE health_daily ADD COLUMN parse_shortfall jsonb NULL;
ALTER TABLE health_fleet_daily ADD COLUMN parse_shortfall jsonb NOT NULL DEFAULT '{}'::jsonb;
