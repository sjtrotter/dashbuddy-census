# Operator runbook

## Deploy

Follow [self-hosting](SELF-HOSTING.md). Review the tag's CI, SBOM, and vulnerability scan; verify its Cosign signature and pin the manifest digest in Compose. Set `IMAGE_DIGEST=sha256:...` in `.env` if it should appear in public policy (an image cannot embed its own final digest). Pull, then `docker compose up -d`; check `/healthz`, `/readyz`, and `/v1/policy`. Migrations run synchronously before the listener starts and failure aborts startup. Do not roll back across an incompatible migration without a database plan.

## Deploy (AWS)

Follow the [AWS deployment runbook](../deploy/aws/README.md) for the S2 Graviton Compose host: bootstrap state, apply Terraform, set Parameter Store values, configure DNS, verify, and deploy through GitHub OIDC/SSM. It also covers budgets, backup retention, and the fresh-box restore drill. Instance replacement deletes the local database volume; verify a backup and plan recovery before applying it.

## Rotate the operator token

Operator authentication is planned; S1 validates configuration but has no operator routes. Generate and retain a high-entropy token in the operator's password manager. Put only its 64-digit SHA-256 hex digest in `OPERATOR_TOKEN_SHA256`. Never put the original token in `.env`, tickets, or request logs. Replace the digest and recreate census with `docker compose up -d --force-recreate census`; future authenticated clients must switch to the new token. No overlapping-token window is implemented.

## Backup and restore

Run `./backup.sh` from `deploy/compose` using a daily scheduler. It writes a restricted-permission compressed `pg_dump` and prunes local dumps to 14 UTC dates. Export `BACKUP_BUCKET` to enable `aws s3 cp`; configure bucket encryption, restricted access, and a 14-day lifecycle including noncurrent object versions. Local files and S3 copies both contain sensitive pseudonymous data.

For recovery, use a fresh PostgreSQL volume/database, start only `postgres`, and run `./restore.sh /absolute/path/to/backup.sql.gz`. Type `RESTORE` at the prompt. The script stops census, refuses a database with user relations, and restores in one transaction with SQL errors fatal. It never drops an existing database. Review the restored data, reapply later withdrawals when that feature exists, then start census and check readiness. Schedule a restore drill; copying a dump alone does not prove recovery.

## Revoke an install — planned

`DELETE /v1/installs/me` will let an install withdraw. Operator revocation, cascade deletion rules, and a durable procedure to reapply withdrawals after restore are future work. Foreign keys currently do not cascade. Do not pretend a manual `DELETE FROM installs` is a complete withdrawal procedure.

## Incident: what we can and cannot see

The future database can show pseudonymous install IDs, key hashes, token hashes, structural fingerprints, day-level counts, and reviewed vocabulary. Skeleton storage must not contain screen plaintext below the promotion gate. The S5 trusted-envelope exception is described below. No storage path may retain bearer tokens, IP addresses, or device identifiers. Nonce and revocation timestamps are explicit exceptions to date-only observations. S1 request logs have known route, method, status, elapsed duration, and an optional eight-hex-character install-ID prefix; unmatched paths are redacted.

These choices limit attribution and request reconstruction. Preserve only the minimum permitted evidence, disable affected entry points, rotate credentials where relevant, and publish the impact and recovery actions. Audit host, proxy, cloud, and log-collector settings too: their default logging could violate the intended retention promise. Ingest admission, poisoning defenses, and scheduled retention must ship before enrollment is enabled.

## S5 trust and capture handling

Enrollment leaves `installs.trusted` false. An operator may set that flag through controlled SQL; no `/ops/*` routes are supplied in this slice. Trust unlocks `POST /v1/envelopes` and the trusted-install silence alarm. Every install may submit skeletons and health reports. Trust does not bypass authentication, sensitive scanning, validation, quality limits, or shared budgets. Play Integrity decoding and attestation changes are outside this slice; the existing attestation column is untouched.

A trusted envelope contains an **already-redacted `uinode.v1` capture**, including UI chrome and its capture metadata. It is not a skeleton and may contain plaintext UI strings and the capture's timestamp. The server repeats the public contract's sensitive-marker scan, rejects hits, and removes `metadata.deviceFingerprint` and `metadata.rulesetSignature` before re-serializing and storing. This scan is a backstop, not a general redactor. The envelope's database fingerprint remains NULL; app-side pairing uses `captureId`. Logs never contain captures, payload strings, fingerprints, hashes, bodies, or secrets. A sensitive rejection WARN contains only the public contract marker name.

The six-hourly purge deletes envelopes whose `purge_after` is before the current UTC date, with a default deadline of received day plus 30 days. It also deletes install health rows older than 180 days. The strict date comparison retains a row on its deadline date. Withdrawal synchronously deletes envelope and health rows together with the install's other keyed data. Anonymous fleet rollups remain aggregate history, and accepted health reports recompute each touched rollup from retained daily rows. Backup retention is still 14 days.

## Health alarm catalogue

`HealthAlarms` evaluates stored counters after an accepted health batch, and the existing six-hourly purge cadence evaluates silence before deleting old health history. A single evaluator instance is shared by both entry points. Trust is unnecessary for an install's own rule-death history (k = 1); fleet rules require two reporting installs where specified.

| Kind | Decision |
| --- | --- |
| `silent_rule_death` | In the preceding 28 calendar days, at least three dashing days (admitted ≥ 200), median daily rule count ≥ 5; the reporting day admits ≥ 200 and the rule count is zero or absent. Histories are per install and platform, across app versions. |
| `rule_share_cliff` | Rule share (`count/admitted`) drops by strictly more than 80% versus the latest fleet day of the previous version. Previous means a different version string whose last reporting day precedes the current day; both compared days need at least two installs. No semantic version ordering is inferred. |
| `trips` | Any accepted report's stored trip count exceeds zero. |
| `fleet_unknown` | At least two reporting installs and `unknown/(admitted+unknown)` ≥ 0.5 for the fleet day. |
| `new_clusters` | At least five distinct clusters first seen today for a platform/version, joined through today's cluster sightings. |
| `silence` | A trusted, active install's last health day is at least two UTC days old. A missing last health day cannot establish elapsed time and does not fire. Dates provide a two-day boundary, not hour-resolution measurement. |

Each delivered alarm is counted by kind in process-local `AlarmStats`. The `AlarmSink` seam currently writes WARN lines under logger `Alarm` containing only kind, platform, version, optional eight-character install prefix, and rule IDs. SNS delivery is S6 work. No payload or credentials are included.

Deduplication is in memory by `(kind, full install identity when applicable, version, rule)` for the current UTC day; the full identity is never logged. For rule-list alarms only newly firing rules are delivered. The set resets on a UTC-day change. A process restart may repeat that day's alarms, which is acceptable for this delivery seam. Counters also reset on restart. A trusted install may report a failed pipeline without needing any envelope upload.
