# Operator runbook — S1 skeleton

## Deploy

Follow [self-hosting](SELF-HOSTING.md). Review the tag's CI, SBOM, and vulnerability scan; verify its Cosign signature and pin the manifest digest in Compose. Set `IMAGE_DIGEST=sha256:...` in `.env` if it should appear in public policy (an image cannot embed its own final digest). Pull, then `docker compose up -d`; check `/healthz`, `/readyz`, and `/v1/policy`. Migrations run synchronously before the listener starts and failure aborts startup. Do not roll back across an incompatible migration without a database plan.

## Rotate the operator token

Operator authentication is planned; S1 validates configuration but has no operator routes. Generate and retain a high-entropy token in the operator's password manager. Put only its 64-digit SHA-256 hex digest in `OPERATOR_TOKEN_SHA256`. Never put the original token in `.env`, tickets, or request logs. Replace the digest and recreate census with `docker compose up -d --force-recreate census`; future authenticated clients must switch to the new token. No overlapping-token window is implemented.

## Backup and restore

Run `./backup.sh` from `deploy/compose` using a daily scheduler. It writes a restricted-permission compressed `pg_dump` and prunes local dumps to 14 UTC dates. Export `BACKUP_BUCKET` to enable `aws s3 cp`; configure bucket encryption, restricted access, and a 14-day lifecycle including noncurrent object versions. Local files and S3 copies both contain sensitive pseudonymous data.

For recovery, use a fresh PostgreSQL volume/database, start only `postgres`, and run `./restore.sh /absolute/path/to/backup.sql.gz`. Type `RESTORE` at the prompt. The script stops census, refuses a database with user relations, and restores in one transaction with SQL errors fatal. It never drops an existing database. Review the restored data, reapply later withdrawals when that feature exists, then start census and check readiness. Schedule a restore drill; copying a dump alone does not prove recovery.

## Revoke an install — planned

`DELETE /v1/installs/me` will let an install withdraw. Operator revocation, cascade deletion rules, and a durable procedure to reapply withdrawals after restore are future work. Foreign keys currently do not cascade. Do not pretend a manual `DELETE FROM installs` is a complete withdrawal procedure.

## Incident: what we can and cannot see

The future database can show pseudonymous install IDs, key hashes, token hashes, structural fingerprints, day-level counts, and reviewed vocabulary. It must not contain screen plaintext below the promotion gate, bearer tokens, IP addresses, or device identifiers. Nonce and revocation timestamps are explicit exceptions to date-only observations. S1 request logs have known route, method, status, elapsed duration, and an optional eight-hex-character install-ID prefix; unmatched paths are redacted.

These choices limit attribution and request reconstruction. Preserve only the minimum permitted evidence, disable affected entry points, rotate credentials where relevant, and publish the impact and recovery actions. Audit host, proxy, cloud, and log-collector settings too: their default logging could violate the intended retention promise. Ingest admission, poisoning defenses, and scheduled retention must ship before enrollment is enabled.
