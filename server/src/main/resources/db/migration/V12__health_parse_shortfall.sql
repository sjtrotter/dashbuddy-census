-- #64: per-rule parse-shortfall samples in the health report. Forward-only.
--
-- A report may carry, per rule id, how many of the rule's last 20 eligible matched frames of the day left a required
-- field null: {"<rule id>": k}, k in 0..20. A rule with fewer than 20 eligible frames is left out, so no attempt total
-- is stored. Rule ids and counts only, never screen text. NULL means the report did not carry the counts (an older
-- phone). The per-install row keeps the health table's 30-day retention; the fleet row keeps, per rule, the summed
-- shortfalls and the number of installs that sampled it ({"shortfall": s, "installs": n}, rate s / (n * 20)), after
-- the per-install rows are folded and deleted.
ALTER TABLE health_daily ADD COLUMN parse_shortfall jsonb NULL;
ALTER TABLE health_fleet_daily ADD COLUMN parse_shortfall jsonb NOT NULL DEFAULT '{}'::jsonb;
