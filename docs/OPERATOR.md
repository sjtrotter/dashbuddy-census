# Operator runbook

## Deploy

Follow [self-hosting](SELF-HOSTING.md). Review the tag's CI, SBOM, and vulnerability scan; verify its Cosign signature and pin the manifest digest in Compose. Set `IMAGE_DIGEST=sha256:...` in `.env` if it should appear in public policy (an image cannot embed its own final digest). Pull, then `docker compose up -d`; check `/healthz`, `/readyz`, and `/v1/policy`. Migrations run synchronously before the listener starts and failure aborts startup. Do not roll back across an incompatible migration without a database plan.

## Deploy (AWS)

Follow the [AWS deployment runbook](../deploy/aws/README.md) for the S2 Graviton Compose host: bootstrap state, apply Terraform, set Parameter Store values, configure DNS, verify, and deploy through GitHub OIDC/SSM. It also covers budgets, backup retention, and the fresh-box restore drill. Instance replacement deletes the local database volume; verify a backup and plan recovery before applying it.

## Rotate the operator token

Operator authentication uses the operator token directly as a bearer or with TOTP to establish a browser session. Generate and retain a high-entropy token in the operator's password manager. Put only its 64-digit SHA-256 hex digest in `OPERATOR_TOKEN_SHA256`. Never put the original token in `.env`, tickets, or request logs. Replace the digest and recreate census with `docker compose up -d --force-recreate census`; authenticated clients must switch to the new token and browser sessions are lost on restart. No overlapping-token window is implemented.

## Backup and restore

Run `./backup.sh` from `deploy/compose` using a daily scheduler. Before `pg_dump`, it runs `./export-withdrawals.sh`: an atomic, restricted-permission CSV export to `backups/withdrawals/withdrawals-<UTC timestamp>.csv`. An export or upload failure aborts the backup. Export `BACKUP_BUCKET` to upload journals under `withdrawals/` and dumps at the bucket root. Both retain 14 local UTC dates; configure bucket encryption, restricted access, and the 14-day lifecycle including noncurrent versions. Local files and S3 copies contain sensitive pseudonymous data. The exporter can also run separately between backups.

Each live withdrawal writes a `withdrawals` tombstone in the same transaction as erasure: only the lowercase SHA-256 of the canonical install UUID and `withdrawn_at`, never the UUID or credential. Tombstones last 60 days, exceeding the restorable dump/version horizon. Every startup migrates and **automatically replays tombstones before opening the HTTP listener**; replay failure aborts startup. Live withdrawal and replay share the same deletion implementation, including clearing drafts derived from erased envelopes. Replay only deletes generations whose `enrolled_at <= withdrawn_at`; a later re-enrolment, including one on the same day, survives. V3 backfills legacy enrolments from `created_day` at UTC midnight because V1 retained no precise enrolment instant.

A withdrawal also queues an alarm-spool notice for the host's `census-alarm-publish` SNS publisher. Its message is three lines — `kind=withdrawal`, `install_id_hash=<64 lower hex>`, `withdrawn_at=<ISO-8601 UTC instant>` — and the email subject is `census alarm: withdrawal`; the last two lines ARE a journal CSV row. **Host prerequisite:** the host's `census-alarm-publish` script (written by cloud-init) must carry the `withdrawal` grammar from this version — on a host provisioned before it, update `/usr/local/sbin/census-alarm-publish` from the current `deploy/aws/cloud-init.yaml.tftpl` (or rebuild the instance) or the notice is counted `rejected` and the CSV export is the only off-host journal. Those two values are a replay journal row; copy them as `<64hex>,<ISO-8601 UTC>` into a CSV (optional header `install_id_hash,withdrawn_at`). The hash stays out of application logs. The container cannot use the instance role (IMDS hop limit 1); the host handles SNS and S3 delivery. Deploy the updated host publisher grammar as well as the server and backup scripts.

For recovery, use the operator's SSO identity to download the **latest** journal from the bucket's `withdrawals/` prefix, even when restoring an older dump. Add any newer SNS notice rows. Start only `postgres` with a fresh volume/database, then run:

```sh
./restore.sh /absolute/path/to/census-YYYY-MM-DD.sql.gz /absolute/path/to/withdrawals-latest.csv
```

Type `RESTORE` at the prompt. The script stops census, refuses a database with user relations, and restores in one transaction with SQL errors fatal. With census still stopped, it runs `--reapply-withdrawals /journal.csv` to migrate, merge the journal (keeping the latest instant per hash), and replay. A failure leaves census stopped. The one-shot container uses the caller's UID/GID to read the mode-0600 journal mount. Review the replay counts and restored data, then start census and check readiness; startup replays again, idempotently. To merge another journal into an already restored database while census is stopped:

```sh
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
    -v /absolute/path/to/withdrawals-latest.csv:/journal.csv:ro census --reapply-withdrawals /journal.csv
```

`restore.sh` refuses a missing second argument. Use `--no-journal` **only when no journal exists**: it warns loudly and replays only tombstones inside the dump, which cannot cover later withdrawals.

**Residual:** a withdrawal after the latest journal export needs its SNS email to survive total host loss (the notice is exempt from spool eviction but still rides the publisher's hourly token budget, so a health-alarm storm can delay it; and a `withdrawals` row older than 60 days is purged, which is longer than any restorable backup). A legacy install (enrolled before this version) carries a day-start `enrolled_at`, so the same-day re-enrolment guard is exact only for enrolments made after the upgrade; paste the notice's hash and instant into the CSV. If host loss occurs before SNS publication as well, neither off-host channel has that withdrawal and it cannot be recovered automatically. The existing spool is bounded/best-effort and SNS publishing is rate-limited, so confirm receipt and retain the emails; exporting more often narrows the gap.

**Last resort when no journal exists:** if you still know the withdrawn UUID, keep census stopped and erase it in one transaction. `InstallStore.WITHDRAWAL_TABLES` owns this list. Preserve a hashed tombstone for subsequent restores:

```sql
BEGIN;
SELECT 1 FROM installs WHERE install_id = '<uuid>' FOR UPDATE;
INSERT INTO withdrawals (install_id_hash, withdrawn_at)
VALUES (encode(sha256('<uuid>'::uuid::text::bytea), 'hex'), CURRENT_TIMESTAMP)
ON CONFLICT (install_id_hash) DO UPDATE SET withdrawn_at = GREATEST(withdrawals.withdrawn_at, EXCLUDED.withdrawn_at);
WITH erased AS (
    DELETE FROM trusted_envelopes WHERE install_id = '<uuid>' RETURNING id
)
UPDATE clusters SET draft = NULL, draft_day = NULL
WHERE draft->>'envelopeId' IN (SELECT id::text FROM erased);
DELETE FROM health_daily      WHERE install_id = '<uuid>';
DELETE FROM token_sightings   WHERE install_id = '<uuid>';
DELETE FROM cluster_sightings WHERE install_id = '<uuid>';
DELETE FROM ingest_ledger     WHERE install_id = '<uuid>';
DELETE FROM nonces            WHERE install_id = '<uuid>';
DELETE FROM installs          WHERE install_id = '<uuid>';
COMMIT;
```

Run `./export-withdrawals.sh` after this manual fallback. The first restore drill (2026-10-03) demonstrated why the durable journal is necessary; see the [AWS drill log](../deploy/aws/README.md#drill-log).

Schedule a restore drill; copying a dump alone does not prove recovery.

## Revoke or withdraw an install

`DELETE /v1/installs/me` lets an authenticated install withdraw. The operator can revoke credentials using the endpoint below. Revocation retains stored observations; withdrawal deletes install-keyed rows in one transaction. See [Backup and restore](#backup-and-restore) for mandatory tombstone replay and journal recovery. Foreign keys currently do not cascade. Do not pretend a manual `DELETE FROM installs` is a complete withdrawal procedure.

## Edge rate limiting (#1178)

The public site runs `caddy-ratelimit` zones keyed by the direct client address: `/v1/enroll` 10 per
minute AND 20 per hour, all of `/v1` (the enrol request spends both) 200 per minute — sliding windows,
10 % jitter on the `Retry-After`, a 15-second sweep. A refused request is `429` with `Retry-After`
from Caddy and never reaches the application, so it spends none of the application's shared buckets.
What that buys: one address can take at most a sixth of the application's hourly enrol allowance
(120/h, spent before credential checks) and a third of its global 600/min admission, so emptying the
shared buckets in seconds from one curl is over — starving them now costs at least six addresses
(enrol) or three (ingest); the application-side scope of those buckets is the real weakness and is
filed separately. 200/min per address still lets ~66 phones behind one carrier address each run a
three-batch upload in the same minute; a phone treats an edge `429` as a deferral (spool kept, run
rescheduled). The client address exists only inside the Caddy process for one window plus the sweep;
nothing is stored. The plugin's refusal line DOES carry the peer address (logger
`http.handlers.rate_limit`), and it is discarded by the global default logger in the Caddyfile — not
by the site logs — which is how ADR-0011's "no IP ever" posture is kept; keep that global block. The
limits reset on a Caddy restart. The operator listener has no per-IP zone: it is reachable only
through the VPN from registered peers and keeps the application's 60/minute operator bucket. A load
check after each edge rollout, from one address: 201 `GET /v1/policy` inside a minute must answer
`429` from the 201st on (and `200` again from another address); use `GET /v1/policy`, never bogus
enrols, which would spend the shared enrol budget.

## Incident: what we can and cannot see

The future database can show pseudonymous install IDs, key hashes, token hashes, structural fingerprints, day-level counts, and reviewed vocabulary. Skeleton storage must not contain screen plaintext below the promotion gate. The S5 trusted-envelope exception is described below. No storage path may retain bearer tokens, IP addresses, or device identifiers. Nonce and revocation timestamps are explicit exceptions to date-only observations. S1 request logs have known route, method, status, elapsed duration, and an optional eight-hex-character install-ID prefix; unmatched paths are redacted.

These choices limit attribution and request reconstruction. Preserve only the minimum permitted evidence, disable affected entry points, rotate credentials where relevant, and publish the impact and recovery actions. Audit host, proxy, cloud, and log-collector settings too: their default logging could violate the intended retention promise. Ingest admission, poisoning defenses, and scheduled retention must ship before enrollment is enabled.

## S5 trust and capture handling

Enrollment leaves `installs.trusted` false. An operator sets that flag through the TOTP-protected `/ops/installs/{uuid}/trust` endpoint below. Trust unlocks `POST /v1/envelopes` and the trusted-install silence alarm. Every install may submit skeletons and health reports. Trust does not bypass authentication, sensitive scanning, validation, quality limits, or shared budgets. Play Integrity decoding and attestation changes are outside this slice; the existing attestation column is untouched.

A trusted envelope contains an **already-redacted `uinode.v1` capture**, including UI chrome and its capture metadata. It is not a skeleton and may contain plaintext UI strings and the capture's timestamp. The server repeats the public contract's sensitive-marker scan, rejects hits, and removes `metadata.deviceFingerprint` and `metadata.rulesetSignature` before re-serializing and storing. This scan is a backstop, not a general redactor. An envelope is paired to a cluster only when its declared top-level `fingerprint` names an EXISTING cluster of the SAME platform; the phone uploads skeletons first (there is no retroactive pairing), and an envelope naming no cluster or an unknown one is stored with a NULL fingerprint. The envelope's `fingerprint` is DECLARED by the phone and taken on trust because the server cannot recompute an app-side skeleton, so a trusted install can attach its capture to any same-platform cluster; this is acceptable while trusted means the operator's own device. Logs never contain captures, payload strings, fingerprints, hashes, bodies, or secrets. A sensitive rejection WARN contains only the public contract marker name.

The six-hourly purge deletes envelopes whose `purge_after` is before the current UTC date, with a default deadline of received day plus 30 days. It also deletes install health rows older than 180 days. The strict date comparison retains a row on its deadline date. Nonce, ledger, envelope, and health purges each delete in 1,000-row `ctid` batches, with at most 50 batches per sweep per run and a coroutine cancellation check between batches. Each batch commits separately. INFO logs report `purge sweep=<name> deleted=<n> batches=<k> capped=<bool>`; a capped sweep leaves remaining eligible rows for the next run. Silence evaluation and each delete sweep have independent failure guards: one failure logs a single WARN `purge sweep=<name> failed class=<exception class simple name>` without its message, and the remaining sweeps continue. Cancellation propagates. Withdrawal synchronously deletes envelope and health rows together with the install's other keyed data. Anonymous fleet rollups remain aggregate history, and accepted health reports recompute each touched rollup from retained daily rows. Backup retention is still 14 days.

## Health alarm catalogue

`HealthAlarms` evaluates stored counters after an accepted health batch, and the existing six-hourly purge cadence evaluates silence before deleting old health history. A single evaluator instance is shared by both entry points. Trust is unnecessary for an install's own rule-death history (k = 1); fleet rules require two reporting installs where specified.

| Kind | Decision |
| --- | --- |
| `silent_rule_death` | In the preceding 28 calendar days, at least three dashing days (admitted ≥ 200), median daily rule count ≥ 5; the reporting day admits ≥ 200 and the rule count is zero or absent. History and the reporting day are aggregated per install/platform/day across all stored app versions, including versions accepted in earlier batches. Admitted, unknown, trips, and per-rule counts are summed; the lexically greatest version is only the reporting-day alarm label. |
| `rule_share_cliff` | Rule share (`count/admitted`) drops by strictly more than 80% versus the latest fleet day of the previous version. Previous means a different version string whose last reporting day precedes the current day; both compared days need at least two installs. No semantic version ordering is inferred. |
| `trips` | Any accepted report's stored trip count exceeds zero. |
| `fleet_unknown` | At least two reporting installs and `unknown/(admitted+unknown)` ≥ 0.5 for the fleet day. |
| `new_clusters` | At least five distinct clusters first seen today for a platform/version, joined through today's cluster sightings. |
| `silence` | A trusted, active install has gone at least 48 elapsed hours since its last accepted health batch (or evaluator startup if none has been received), AND its stored last health day is at least two UTC days old. A missing stored day does not fire. A fresh backfill resets the elapsed clock regardless of the report's day. |

Each delivered alarm is counted by kind in process-local `AlarmStats`. The `AlarmSink` always writes WARN lines under logger `Alarm`, the system of record, containing only kind, platform, version, optional eight-character hex install prefix, and rule IDs. The shared renderer validates the kind against the catalogue and all other fields against their token grammars; invalid values become `[redacted]`. Alarm evaluation failures after a committed health batch log one WARN `alarm_evaluation_failed class=<simple name>` under `Alarm`, without the exception message; the accepted response remains HTTP 200. Cancellation propagates. Dedupe keys are recorded only after successful sink delivery, so a throwing sink can be retried on the next evaluation.

Alarm delivery is **WARN log + spool file → host publisher → SNS email**. `ALARM_SPOOL_DIR` enables `FileSpoolAlarmSink` and must be an absolute path; unset selects `LoggingAlarmSink` only. Compose sets `/var/spool/census-alarms` on the writable `alarm-spool` named volume; the container root stays read-only. On AWS the volume binds to `/var/lib/census-data/alarms`, mode 0700 and owned by uid/gid 10001. The app logs first, then writes exactly the shared rendering to `<epochMillis>-<counter>.alarm.tmp` and atomically renames it to `.alarm`. At the default 64-file cap it drops the oldest copy first. I/O failures increment `spool_failed` in process-local `AlarmStats` and emit at most one WARN per ten minutes, `alarm_spool_failed class=<simple name>`, without the exception message or path; health admission and purge continue. Counters and warning throttling reset on restart.

On AWS, Terraform provisions `alerts_topic_arn` in SSM from the existing SNS topic; the host renderer writes optional `ALERTS_TOPIC_ARN` into `/opt/census/.env`. The app has no topic configuration. The root Python host publisher treats all spool entries as hostile: directory-relative opens with `O_NOFOLLOW` and `fstat` require regular files owned by uid 10001, one link, and at most 4 KiB. Names must be numeric `<epochMillis>-<counter>.alarm`; strict UTF-8 messages must match exactly the five ordered fields and token grammars below. Other names (including hidden and temporary files), directories, symlinks, hard links, wrong-owner/oversized files and invalid messages are rejected and removed without following links. The host retains at most the newest 256 valid files per scan, evicting the oldest overflow; concurrent writes can require another scan. The app's ordinary cap remains 64.

The publisher attempts at most 16 oldest valid files per run with a 20-second AWS CLI timeout each. A persistent root-owned 0600 token bucket at `/var/lib/census-data/alarm-publish-state.json`, outside the spool, holds at most 20 tokens and refills at **20/hour**. Every attempt consumes a token durably before publishing, even on failure. Empty-budget and run-limit deferrals remain for later runs or storage eviction. A corrupt state fails closed and needs operator repair; reboot/upgrade preserves the budget. The hardened systemd service has a five-minute timeout, `ProtectSystem=strict`, writable paths limited to spool/state/root AWS cache, `PrivateTmp=yes` and `NoNewPrivileges=yes`. The path unit watches the spool; a five-minute timer retries failures. Success deletes the entry; failure retains it. Journal output is counts only: `census-alarm-publish: published=<n> failed=<n> rejected=<n> deferred=<n>` and, when exhausted, one `census-alarm-publish: budget exhausted, deferred=<n>` line. No file content, name, exception text or AWS output is journaled. Missing/`CHANGE-ME` optional parameters are omitted; no topic makes the publisher exit silently while the app continues logging and spooling. Existing hosts need the [one-time publisher and Compose upgrade](../deploy/aws/README.md#operator-second-factor--alarm-delivery).

Alarm email arrives through the **existing confirmed SNS subscription**. Its subject is `census alarm: <kind>` and its body contains exactly `kind`, `platform`, `version`, `install_prefix`, and `rule_ids` as `name=value`, one field per line, using validated tokens. The host accepts `[redacted]` only for platform/version; the prefix is eight lowercase hex characters or `-`, and rule IDs form a bracketed list matching the wire grammar (two to five dot-separated lowercase segments). The body never includes payload strings, captures, full install IDs, fingerprints, hashes, bearer tokens, TOTP codes/secrets, a topic ARN/account ID, or exception messages. AWS's email envelope and unsubscribe links are outside the application body. Spool overflow or I/O failure may lose email copies; a crash after publishing but before deletion may duplicate an email. Today's delivered alarms and kind counters record local delivery, not SNS acknowledgement.

**No container holds or can obtain AWS credentials.** IMDSv2 requires tokens and uses hop limit 1; the host alone uses the instance role. The census container performs no IMDS or SNS calls. The host's existing IAM grant remains scoped to `sns:Publish` on the alarm topic.

Deduplication is in memory by `(kind, full install identity when applicable, platform, version, rule)` for the current UTC day; the full identity is never logged. For rule-list alarms only newly firing rules are delivered. The set resets on a UTC-day change. A process restart may repeat that day's alarms, which is acceptable for this delivery seam. Counters also reset on restart. A trusted install may report a failed pipeline without needing any envelope upload.

The silence clock uses a process-local map of install IDs to server receipt instants, updated on every accepted health batch, and an evaluator startup instant for installs without an entry. After a process restart the silence clock restarts: it fails toward NO alarm for the first 48 hours; the stored-day check still bounds it. No receipt timestamp is added to the database.


## S6a operator surface

`GET /ops/` is a read-only, server-rendered dashboard with identity and snapshot sections followed by alarms, seven days of health, a cluster summary table, today's ledger, installs, and the hash-free vocabulary queue.
The cluster table has one row per platform and app version, with total and per-status counts. Platforms sort alphabetically, numeric versions newest first, and “Not recorded” last within each platform. A cluster observed in multiple versions contributes to each corresponding row.
Each Review link opens `GET /ops/clusters/view?platform=doordash&version=8.10`. This authenticated child page shows 25 clusters per page, untriaged (`new`) first, followed by `triaged`, `drafted`, `resolved`, and `ignored`; within each status, rank descends. It has status filters, Previous/Next links, and the ranking explanation. Required `platform` matches `[a-z_][a-z0-9_]{0,31}`; required `version` is a platform app version or `none` for clusters with no recorded version, including sightings recorded as `unknown`. Optional `status` uses those five statuses, and optional `page` is an integer from 1 to 2,147,483,647 (default 1, clamped to the last available page). Invalid or missing filters return an HTML 400 page. Status links reset paging.
`GET /ops/clusters/{fingerprint}/view` is the authenticated HTML cluster detail page with gated notes, a trusted-capture screen wireframe, and skeleton samples. Its only form is the logout control; it has
no JavaScript or embedded operator credential. Its CSP is
`default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'`.

The **Screen wireframe** section precedes skeleton samples. It draws the newest paired, already-redacted `uinode.v1` capture from a **currently trusted, unrevoked install**, with clipped node bounds, text/description/hint/pane labels, class and view-ID titles, and highlighted clickable boxes. The facts line shows receipt day, the eight-character install prefix, platform app version when recorded, and drawn/visited node counts. Rendering visits at most 2,000 nodes through depth 64. When no capture is paired, the section shows an explanatory empty state; clearing trust or revoking the install removes its capture from selection immediately. Pairing requires the phone to name an existing cluster's fingerprint when uploading the envelope.

The wireframe uses numeric-only inline styles for frame aspect ratio and box percentages, the only inline style attributes on ops pages. The existing CSP permits them; the page still uses one constant stylesheet and no JavaScript. Labels and titles pass through the existing privacy masking and HTML escaping. Skeleton samples and the JSON cluster-detail response are unchanged; the wireframe is never serialized into that JSON.

For a browser, open `GET /ops/login` and enter the operator token and the authenticator's
6-digit code. The token is the same bearer value; the server never echoes it and the cookie never carries it —
decline the browser's offer to save it (`autocomplete=off` is only advisory). Success sets `census_ops` with `HttpOnly; Secure; SameSite=Strict; Path=/ops`, a 12-hour
lifetime and a 1-hour idle timeout. There is ONE active session: a new login elsewhere logs the
old browser out, and a process restart loses the session. Reads accept the cookie or
`Authorization: Bearer <operator token>`; mutations still need a fresh `X-Census-Totp` header
(the cookie never carries the second factor forward). `POST /ops/logout` ends the session and
expires the cookie; it requires authentication but no TOTP. A wrong token never consumes a code.
Login and mutations share the same 90-second replay memory, and login shares the 60/minute ops
bucket. A browser `GET` under `/ops` without a valid session is redirected to
`/ops/login` (API callers without `Accept: text/html` keep the JSON `401`). Every authenticated
`/ops` response and both pages carry `Cache-Control: no-store`, so nothing renders from a cache after
logout, and both pages send `frame-ancestors 'none'`; a malformed percent-escape anywhere in a query,
cookie or form is a `400`, never a `500`.

`/ops` is not routed on the public host at all (404); it is served only on the operator listener
`https://OPS_BIND_IP:8443` — the WireGuard address on AWS (see the runbook's "Operator VPN"), loopback
elsewhere — with Caddy's internal CA (#1181). Except for `GET /ops/login` and `POST /ops/login`, `/ops/*`
endpoints require a valid bearer or session cookie. An explicit wrong Authorization header is refused even with a valid cookie.
Missing or incorrect credentials return only `401 {"error":"unauthorized"}`; failed sign-in
shows only "Sign-in failed." without identifying the factor. The shared `ops` rate bucket allows
60 requests per minute. Each request logs one INFO line under `Ops`, with method, a known
`/ops/<first segment>` path (`clusters`, `installs`, `health`, `alarms`, `ledger`, `vocabulary`,
`login`, or `logout`; no parameters or query), and status. Unknown segments reduce to `/ops`.
No body, token, TOTP code or secret, cookie value, hash, payload, or full install ID is logged.

Mutations other than logout additionally require `X-Census-Totp`, a six-digit RFC 6238 HMAC-SHA1 code. Configure
`OPERATOR_TOTP_SECRET` with a canonical uppercase, unpadded RFC 4648 base32 secret of 16–64
characters. Invalid configuration aborts startup without echoing the value. Codes use 30-second
steps with a one-step tolerance in either direction. Accepted codes cannot be reused for 90
seconds in the current process; use a fresh code for each mutation. Missing/wrong codes return
`401 totp_required`, reuse returns `401 totp_replayed`, and an unset secret returns
`503 totp_unconfigured`. Bearer-authenticated reads remain available with no TOTP secret.
On AWS, provision the secret locally and store it as the SSM SecureString
`/dashbuddy-census/operator_totp_secret`; `/usr/local/sbin/census-configure` reads it into the
root-owned 0600 `/opt/census/.env`. Follow [Operator second factor + alarm delivery](../deploy/aws/README.md#operator-second-factor--alarm-delivery)
for local generation, authenticator enrolment, Terraform apply, and the one-time renderer
replacement on existing hosts whose cloud-init is frozen. Restart `census.service` after changing
the parameter. A missing/`CHANGE-ME` secret is omitted, so login and mutations other than logout fail closed with the 503 above;
it does not refuse server startup. Never generate, print, or distribute the secret through SSM Run Command.

### Display gate (#1175)

The #1175 ruling excludes trusted
installs from the k count. At least `Policy.k` distinct non-trusted installs in the last 28 UTC
days, **or any trusted sighting**, unblinds a cluster's class/id labels. The trusted-device
exception is k = 1 for the operator's own device. Below the gate, every class/id is replaced
by `~` and the first eight hex characters of its SHA-256 digest. Text slots show only kind
badges at every gate level; their token hashes are never rendered in a sample or dashboard.
Install, sighting, version, and day counts remain visible. Raw sample JSON is never returned.
Vocabulary hashes are available only on the dedicated JSON vocabulary surface.

### JSON reads

| Endpoint | Result |
| --- | --- |
| `GET /ops/clusters?platform=&version=&status=&limit=` | Array of `{platformAppVersion, clusters}` groups, numeric versions newest first; optional filters must be valid when supplied. The additive `platform` filter uses `[a-z_][a-z0-9_]{0,31}` and restricts clusters before version grouping; the JSON shape is unchanged. Default 50 rows, maximum 200 across groups. Rows include dates, counts, trust/gate flags, versions, `newWithVersion`, status, resolved rule, and notes. |
| `GET /ops/clusters/{fingerprint}` | Cluster row and `{platformAppVersion, receivedDay, skeleton}` samples; `newWithVersion` refers to its newest observed version. |
| `GET /ops/installs?limit=` | Prefixes, dates, trust/revocation flags, last app version, and whether an attestation verdict exists. Default 50, maximum 200. |
| `GET /ops/health?days=7` | `fleet` rows and per-install prefix/day/platform/version counters; maximum 90 days. |
| `GET /ops/alarms` | Today's successfully delivered alarms and process-local `AlarmStats` counts. |
| `GET /ops/ledger?day=YYYY-MM-DD` | Per-install prefix counters and totals; defaults to today. `batches` is a count, never the batch IDs. |
| `GET /ops/vocabulary/queue?limit=` | Hash, kind, distinct non-trusted install count, first/last day for eligible hashes absent from vocabulary; default 50, maximum 200. |

Cluster ranking is computed at read time as `distinctInstalls28d × log2(1 + sightings28d) ×
recency`, where recency is 1.0 within 7 days, 0.5 within 28 days, and 0.1 otherwise. The 28-day
counts include today and the preceding 27 UTC dates. A cluster is new with a version when its
first-seen day is at least that platform/version's first sighting day and it has no sighting
under a numerically older version. Nullable JSON fields are omitted. An install app-version value outside the existing version grammars is omitted from the operator view.

### Mutations with curl

Set `CENSUS_URL` to the server origin, `OPERATOR_TOKEN` from the password manager, and `TOTP`
to a fresh code before **each** command. Do not enable shell tracing or curl verbose output.
Read the full install UUID from the phone's developer settings; the surface shows prefixes only.
These examples use environment placeholders, never embedded credentials.

```sh
curl --fail-with-body "$CENSUS_URL/ops/clusters/$FINGERPRINT/status" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' \
  -d '{"status":"resolved","resolvedRuleId":"doordash.screen.offer","notes":"Reviewed"}'

curl --fail-with-body "$CENSUS_URL/ops/installs/$INSTALL_UUID/trust" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' -d '{"trusted":true}'

curl --fail-with-body -X POST "$CENSUS_URL/ops/installs/$INSTALL_UUID/revoke" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP"

curl --fail-with-body "$CENSUS_URL/ops/vocabulary/resolve" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' \
  -d "{\"tokenHash\":\"$TOKEN_HASH\",\"clearText\":\"Looking for offers\",\"source\":\"corpus\",\"reject\":false}"

curl --fail-with-body "$CENSUS_URL/ops/vocabulary/resolve" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' \
  -d "{\"tokenHash\":\"$TOKEN_HASH\",\"clearText\":null,\"source\":\"corpus\",\"reject\":true}"
```

Status accepts `new`, `triaged`, `drafted`, `resolved`, or `ignored`; notes are at most 2,000
characters and the optional resolved rule must match the wire rule-ID grammar. Set `trusted`
to false to remove trust. Successful mutations return 204; missing target rows return 404.
Revocation is idempotent and immediately blocks the install's signed requests.

Vocabulary source is `trusted:<eight-hex-prefix>`, `corpus`, or `rule_anchor`. Clear text is at
most 128 characters. Resolution proves `CensusHash.of(clearText) == tokenHash`; mismatch returns
422 `hash_mismatch`. Rejection stores NULL clear text. Resolution requires current non-trusted
k eligibility, and never overwrites an existing vocabulary row. Queue promotion/nightly work
and `shipped` export remain M4/#641.

## Classify & draft

Open **Classify & draft** on a cluster detail page. This is a server-rendered form with no
JavaScript and the same restrictive CSP as the dashboard. With no currently trusted,
unrevoked paired capture, it offers classification and notes only. Saving classification
alone leaves the cluster status and any saved draft unchanged; “unknown” clears the class.
Cluster cards show the class beside status; the dashboard summary and review header show
counts by class, including unclassified clusters.

Two saves exist on a cluster that has a trusted capture: **Save classification only** records the screen class (and notes) with no draft and no intent — the right action for a `noise` or `sensitive` screen — while **Save draft** runs the generator and needs an intent, at least one anchor and the shape's required fields.

With an eligible capture, the page numbers the drawn wireframe boxes. Choose a screen class,
shape, intent and priority, then mark anchors, fields, binds and redactions by those numbers.
Each field row has a second field slot and an optional strip prefix (up to 40 characters),
so one label can supply two fields and a fused label can have its fixed prefix removed.
Both field slots share the row's transform and prefix. Up to four typed constants are
available; only plain boolean, integer and string fields of the chosen shape qualify.
The required-field checklist also includes the shape's one-of requirements.

**Apply shape** refreshes the available fields and preserves the other controls, including
choices that need correction for the new shape. **Preview draft** shows either generator
refusals or JSON5 and warnings. Both operations are pure and require authentication and
rate admission, but no TOTP. **Save** requires a fresh six-digit code in its TOTP field.
A TOTP is consumed when the gate accepts the request, even if the handler then refuses
the save (400/409/422), so a refused save needs the next code.
The field shares replay protection with login and the header-based API; sending a code
in both places is refused. Codes are never echoed on a re-rendered page. A successful save
redirects to cluster detail, which offers **Draft (JSON5)**.

The form pins its immutable capture using only its row ID (`envelopeId`). No digest is
round-tripped through the form. The server reloads that exact row and checks trust and
revocation before preview or save, computing `envelopeSha256` server-side at save time
for provenance inside the stored draft document. A newer upload does not replace an open
form's capture. A stale or ineligible capture produces “This capture changed — reload”. Node selections resolve through the
contract walk's child-index paths, never through labels or display numbers alone.

Draft forms are bounded to 64 KiB, 1,000 unique parameters and 2,000 characters per value. At most
150 eligible drawn nodes appear in the table, with a remainder count; six controls per row
keep a full form below the parameter limit. At most 64 active assignments are allowed,
counting both field slots and constants separately. All processing stays on the server.

Each cluster has one replaceable draft. Successful generation and save atomically set the
classification, selections, JSON5, private capture provenance, save day and `drafted` status;
the resolved rule stays unchanged. Notes use the existing gated notes column. Refusals
store nothing. Download serves the stored JSON5 bytes exactly as previewed. JSON cluster
responses never contain drafts, selections or capture provenance; classification and draft
presence are the only new public DTO fields. Empty defaults remain omitted, preserving the
previous response for an unclassified cluster without a draft.

A saved draft is an operator artefact that survives install **REVOCATION** (access) or
removal of trust: it remains downloadable, even though the drafting page becomes
classify-only when no eligible capture remains. **WITHDRAWAL** and retention are erasure:
they delete the draft and save day only when its own capture is deleted, in the same
transaction. Deleting another capture in the cluster leaves the draft intact. The operator's
screen classification survives both erasure paths because it is not capture-derived.

Drafts quote trusted-capture text and remain operator-only. Display strings are masked and
HTML-escaped, the hidden capture reference contains only the row ID, and form actions
are relative.
The generator refuses weak anchors; the server also refuses generated output containing
identifiers that would violate the page's privacy rules, keeping preview and download bytes
identical. A draft does not prove recognition safety: **the APP test suite is the gate**,
including compilation and positive/negative corpus checks. The server only drafts.
