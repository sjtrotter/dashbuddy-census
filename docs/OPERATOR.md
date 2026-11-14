# Operator runbook

## Deploy

Follow [self-hosting](SELF-HOSTING.md). Review the tag's CI, SBOM, and vulnerability scan; verify its Cosign signature and pin the manifest digest in Compose. Set `IMAGE_DIGEST=sha256:...` in `.env` if it should appear in public policy (an image cannot embed its own final digest). Pull, then `docker compose up -d`; check `/healthz`, `/readyz`, and `/v1/policy`. Migrations run synchronously before the listener starts and failure aborts startup. Do not roll back across an incompatible migration without a database plan.

## Deploy (AWS)

Follow the [AWS deployment runbook](../deploy/aws/README.md) for the S2 Graviton Compose host: bootstrap state, apply Terraform, set Parameter Store values, configure DNS, verify, and deploy through GitHub OIDC/SSM. It also covers budgets, backup retention, and the fresh-box restore drill. Instance replacement deletes the local database volume; verify a backup and plan recovery before applying it.

## Rotate the operator token

Operator authentication uses the operator token directly as a bearer or with TOTP to establish a browser session. Generate and retain a high-entropy token in the operator's password manager. Put only its 64-digit SHA-256 hex digest in `OPERATOR_TOKEN_SHA256`. Never put the original token in `.env`, tickets, or request logs. Replace the digest and recreate census with `docker compose up -d --force-recreate census`; authenticated clients must switch to the new token and browser sessions are lost on restart. No overlapping-token window is implemented.

## Hash pepper

`CENSUS_HASH_PEPPER` (#85) is the server secret every stored word hash is keyed under: the database holds `HMAC-SHA256(pepper, hash)` in place of the phone's public hash, and a notification's fingerprint and every client batch id are keyed the same way, so `ingest_ledger.batch_ids` never holds the id the phone sent. Create it once with `openssl rand -hex 32` (64 hex digits), with shell tracing disabled, and keep it the way the TOTP seed is kept: in the password manager and in a mode-0600 file under `~/dashbuddy/secrets/` on the operator workstation. On AWS it is the SecureString `/dashbuddy-census/census_hash_pepper`, rendered into the root-only `.env` ([AWS runbook](../deploy/aws/README.md#hash-pepper-85)); self-hosters put it in `.env` ([self-hosting](SELF-HOSTING.md#hash-pepper)). It never belongs in the database, a backup, a ticket or a log.

Startup refuses a missing or malformed pepper, and refuses one that differs from the pepper the database was keyed under (`HashPepperMismatchException` in the startup log): the first migration under #85 (V7) records an HMAC check value in `hash_keying`, never the pepper. **Never rotate it.** A new pepper is a new hash domain; moving to one means rewriting every stored value under it, which is a migration plan, not a configuration change. Back it up beside the TOTP seed: a restore runs under the current `.env`, so a dump taken after V7 comes back only under the same pepper, and a dump taken before V7 is keyed by V7 during the restore. Losing the pepper exposes nothing, but the server then cannot start on that database or its backups.

What it protects: a stolen database, dump or backup cannot be matched against a guess list. What it does not: the live operator, who holds the pepper, can still hash, key and compare any guess, as the vocabulary resolve route does. See [security](SECURITY.md#keyed-storage-85).

## Epoch keys

Content rows (token sightings, cluster sightings, per-install health) carry an epoch pseudonym, not the install id (#86). An epoch is 30 whole UTC days on a fixed grid counted from 1970-01-01. Each epoch's key is 32 random bytes the server generates itself; it lives in the `epoch_keys` table wrapped (AES-GCM) under a key derived from `CENSUS_HASH_PEPPER`, so a dump or backup without the pepper holds no usable epoch key. There is no new secret, parameter or deploy step: the keys need nothing from the operator, and the pepper's backup covers them.

The server keeps the previous, current and next epochs' keys. The lifecycle sweep creates the current and next ones ahead of need and deletes every key older than the previous epoch (`/ops/lifecycle` reports it as `epoch_keys`). Nothing can re-derive a deleted key, not even with the pepper, so its rows can no longer be tied to any install. Retention has deleted those rows by then anyway (every content table keeps at most 30 days, and a key outlives its epoch by a full epoch).

What destruction means, exactly. Deleting the row removes the key from the live database at once; PostgreSQL vacuum reclaims the dead tuple later. Every backup taken before the deletion still holds the wrapped key, and anyone with that backup and the pepper can recover it and re-link that epoch's rows in that backup. Destruction is therefore complete when the last such backup expires: 14 days for the dump, plus up to 14 days for a noncurrent S3 version. Do not keep a copy of a dump past its retention, and never copy the `epoch_keys` rows anywhere else. A restore brings old keys back with the dump; the withdrawal replay uses every key the restored database holds, so it erases rows under them too, and startup's lifecycle pass then deletes the expired keys again before serving.

Withdrawal, "delete my data" and revocation recompute the install's tags under every stored key, an expired one the sweep has not yet deleted included, and delete those rows; a restore replay does the same and skips rows under a deleted key, which nobody can tie to the install. The `installs` table keeps the install id for authentication, quotas, grants and withdrawal; the ledger, nonces and the operator's own trusted envelopes keep it too.

## Backup and restore

Run `./backup.sh` from `deploy/compose` using a daily scheduler. The dump passes through `order-tagged-dump.sh`, which sorts the rows of every epoch-tagged table by tag so a backup does not keep one upload's rows together ([Security](SECURITY.md#epoch-pseudonyms-at-rest-86)); it is still a plain SQL dump that `restore.sh` loads unchanged, and `TaggedDumpOrderTest` restores one. Its sort spills to `backups/`, beside the dump. After `pg_dump` and dump upload, it runs `./export-withdrawals.sh` and `./export-filter-floor.sh`. Withdrawal export is an atomic, restricted-permission CSV export to `backups/withdrawals/withdrawals-<UTC timestamp>.csv`. Either export/upload failure fails the backup operation; the existing dump is preserved, but is not a complete recovery set. Earlier versions warned on withdrawal export failure; this release makes it fatal. Export `BACKUP_BUCKET` to upload journals under `withdrawals/` and dumps at the bucket root. Dumps and withdrawal journals retain 14 local UTC dates; policy-only `filter-floor/` journals are retained without age expiry. For observation backups, configure bucket encryption, restricted access, and the 14-day lifecycle including noncurrent versions. Local files and S3 copies contain sensitive pseudonymous data. The exporter can also run separately between backups.

Each live withdrawal writes a `withdrawals` tombstone in the same transaction as erasure: only the lowercase SHA-256 of the canonical install UUID and `withdrawn_at`, never the UUID or credential. Tombstones last 60 days, exceeding the restorable dump/version horizon. Every startup migrates and **automatically replays tombstones before opening the HTTP listener**; replay failure aborts startup. Live withdrawal and replay share the same deletion implementation, including clearing drafts derived from erased envelopes and recomputing the fleet health totals the erased rows fed. No enrolment instant is stored (#79): a generation enrolled after a withdrawal copies that tombstone's instant into `installs.erased_through`, and replay erases a generation only when its tombstone is newer than that marker (or the marker is empty). A later re-enrolment, including one on the same day, survives. V6 derived the marker from V3's `enrolled_at` before dropping it. Every withdrawal or deletion moves the tombstone strictly forward (the later of the request instant and one microsecond past the stored value), so a clock rollback or a skewed journal row never reuses an instant an older backup's marker covers. The 60-day purge clears a marker and deletes its tombstone together, install row locked first, in one transaction per batch.

A withdrawal, and a "delete my data" request (#79, the same tombstone), also queues an alarm-spool notice for the host's `census-alarm-publish` SNS publisher. It is the one alarm that names an install, as a hash, because a restore needs it to re-apply the erasure; every other alarm names none. Its message is three lines — `kind=withdrawal`, `install_id_hash=<64 lower hex>`, `withdrawn_at=<ISO-8601 UTC instant>` — and the email subject is `census alarm: withdrawal`; the last two lines ARE a journal CSV row. **Host prerequisite:** the host's `census-alarm-publish` script (written by cloud-init) must carry the `withdrawal` grammar from this version — on a host provisioned before it, update `/usr/local/sbin/census-alarm-publish` from the current `deploy/aws/cloud-init.yaml.tftpl` (or rebuild the instance) or the notice is counted `rejected` and the CSV export is the only off-host journal. Those two values are a replay journal row; copy them as `<64hex>,<ISO-8601 UTC>` into a CSV (optional header `install_id_hash,withdrawn_at`). The hash stays out of application logs. The container cannot use the instance role (IMDS hop limit 1); the host handles SNS and S3 delivery. Deploy the updated host publisher grammar as well as the server and backup scripts.

For recovery, use the operator's SSO identity to download the **latest** journal from the bucket's `withdrawals/` prefix, even when restoring an older dump. Add any newer SNS notice rows. Start only `postgres` with a fresh volume/database, then run:

```sh
./restore.sh /absolute/path/to/census-YYYY-MM-DD.sql.gz /absolute/path/to/withdrawals-latest.csv /absolute/path/to/filter-floor-latest.csv
```

Type `RESTORE` at the prompt. The script stops census, refuses a database with user relations, and restores in one transaction with SQL errors fatal. With census still stopped and CURRENT deployment `.env`, it runs `--reapply-withdrawals /recovery/withdrawals.csv --filter-floor /recovery/filter-floor.csv` to migrate, merge withdrawal instants by maximum, merge the floor by maximum, replay both removals and drain lifecycle work. Download the latest `filter-floor/` policy journal alongside the withdrawal journal, regardless of dump age. A failure leaves census stopped. The one-shot container uses the caller's UID/GID to read the mode-0600 journal mount. Review the replay counts and restored data, then start census and check readiness; startup replays again, idempotently. To merge another journal into an already restored database while census is stopped:

```sh
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
    -v /absolute/path/to/withdrawals-latest.csv:/journal.csv:ro census --reapply-withdrawals /journal.csv
```

`restore.sh` requires both recovery journals and refuses missing arguments. A historical dump alone is insufficient. Keep floor journals durable and choose the maximum revision across available exports. The CLI also supports strict `--merge-filter-floor <csv>` and atomic `--export-filter-floor <csv>`; run policy merge with census stopped. Before restarting after a floor increase, export/upload the new floor using `export-filter-floor.sh`.

**Residual:** a withdrawal after the latest journal export needs its SNS email to survive total host loss (the notice is exempt from spool eviction but still rides the publisher's hourly token budget, so a health-alarm storm can delay it; and a `withdrawals` row older than 60 days is purged, which is longer than any restorable backup). Paste the notice's hash and instant into the CSV. If host loss occurs before SNS publication as well, neither off-host channel has that withdrawal and it cannot be recovered automatically. The existing spool is bounded/best-effort and SNS publishing is rate-limited, so confirm receipt and retain the emails; exporting more often narrows the gap.

**Last resort when no journal exists:** if you still know the withdrawn UUID, re-apply the withdrawal through the server's own replay; never by hand. Since #86 the content tables carry epoch tags, not the install id, so only the server can find an install's rows: it recomputes the tags under the stored epoch keys, which only open under the configured pepper, and it erases every table in `InstallStore.WITHDRAWAL_TABLES` (slot sightings included) and refolds the fleet health totals in one transaction. Write a one-row journal and replay it with census stopped and the CURRENT deployment `.env` (the one holding the right `CENSUS_HASH_PEPPER`; a wrong pepper aborts before anything changes):

```sh
umask 077
uuid='<uuid>'   # canonical lowercase
printf '%s,%s\n' "$(printf '%s' "$uuid" | sha256sum | cut -d' ' -f1)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /absolute/path/to/manual-withdrawal.csv
docker compose stop census
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
    -v /absolute/path/to/manual-withdrawal.csv:/journal.csv:ro census --reapply-withdrawals /journal.csv
```

The hash is `sha256` of the UUID text, the same value the `withdrawals` table and the journals hold. `--reapply-withdrawals` migrates, merges the row into `withdrawals` by maximum, erases every generation of that install the tombstone covers (the install row included) and drains lifecycle work, then exits; a failure leaves census stopped and logs the stage, never the id. Check the logged `withdrawal replay matched=1`, then start census. The tombstone is now in the database like any other, so the next journal export carries it.

Run `./export-withdrawals.sh` after this fallback. The first restore drill (2026-10-03) demonstrated why the durable journal is necessary; see the [AWS drill log](../deploy/aws/README.md#drill-log).

Schedule a restore drill; copying a dump alone does not prove recovery.

## Pause uploads

One operator action stops uploads from every non-trusted install without a deploy (#79). Use it when an install or a group of installs is flooding or poisoning the census. `POST /ops/pause` with `{"paused":true}` (operator bearer and a fresh `X-Census-Totp`) answers `{"paused":true,"changedDay":"YYYY-MM-DD"}`; `GET /ops/pause` reads it. The switch is a database row (`ingest_pause`), so it survives restarts and needs no configuration change. While it is on, `POST /v1/skeletons` and `POST /v1/health` from a non-trusted install answer `503 {"error":"paused"}` with `Retry-After: 3600` and store nothing; the app keeps its queue and retries. Trusted installs, enrolment, `/v1/me`, withdrawal and "delete my data" keep working. Each change logs one INFO line `upload pause paused=<true|false>` under `Ops`. Lift it with `{"paused":false}`.

```sh
curl --fail-with-body "$CENSUS_URL/ops/pause" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' -d '{"paused":true}'
```

Per-install quotas apply at admission whether or not the switch is on: per UTC day, 300 accepted skeleton items, 40 batches, 10 MiB and 60 health reports, from `Policy` (`dailySkeletonBudget`, `dailyBatchBudget`, `dailyBytesBudget`, `dailyHealthReportBudget`). A breach answers `429 budget_exhausted` with `Retry-After` to UTC midnight. `/ops/ledger` shows today's per-install counters.

## Revoke, withdraw or delete an install's data

`DELETE /v1/installs/me` lets an authenticated install withdraw; `DELETE /v1/installs/me/data` deletes its server data and keeps it enrolled (#79). The deletion erases every per-install row except the install row and today's ledger row (kept for the quota and batch replay protection, expiring with the 7-day ledger), writes the same tombstone and journal notice as a withdrawal, and is replayed the same way: after a restore from a backup older than the deletion, replay erases that install whole, and the app re-enrols. The response counts the rows erased. The operator can revoke credentials using the endpoint below. Revocation also deletes the install's token, cluster and slot sightings (#86, #80: they no longer name the install, so k counting cannot filter them by a join) and its contributor rows (#81: confirmations, role tags, released slot rows, quarantine items); its health rows stay until retention. Withdrawal deletes every row tied to the install in one transaction: install-keyed rows by id, content rows through its epoch tags under every stored key. See [Backup and restore](#backup-and-restore) for mandatory tombstone replay and journal recovery. Foreign keys currently do not cascade. Do not pretend a manual `DELETE FROM installs` is a complete withdrawal procedure.

## Contributor tier (#81)

Contributors are opted-in installs that review a screen before sending it ([API](API.md#contributor-tier--81)). The
server issues their grants on its own; the operator watches them, revokes abuse, approves gated words and handles the
"Ask the developer" quarantine.

### Operator key

"Ask the developer" items are sealed on receipt to the operator's X25519 public key; the server holds no key that opens
them. Generate the pair once on the operator workstation, never on the host:

```sh
java deploy/tools/QuarantineKey.java keygen ~/dashbuddy/secrets/census-quarantine.key
```

It writes the private key (base64 PKCS#8, mode 0600) and prints the public key. Keep the private key like the TOTP
seed: the password manager and the mode-0600 file. Put the printed public key in `CENSUS_OPERATOR_PUBLIC_KEY`: on AWS
the String parameter `/dashbuddy-census/census_operator_public_key` (the renderer adds it to `.env`; an existing host
needs the renderer refresh, as for any new parameter), for self-hosters `.env`. Without it the server starts, and every
quarantine request answers `503 quarantine_unconfigured`. Changing the key affects only items received afterwards;
items sealed to a lost key cannot be opened by anyone, and expire within 72 hours.

### Quarantine

The quarantine page is `/ops/quarantine/view`; `GET /ops/quarantine` lists the same items as JSON. Nothing else reads
`quarantine_items`: no cluster, count, draft, vocabulary queue, ledger view or agent route. An item expires at the
start of its `expiresDay` (3 UTC days after receipt, at most 72 hours); reads stop showing it then and the next sweep
deletes it. `backup.sh` never dumps its rows (`--exclude-table-data`), so a restore brings none back; an ad-hoc
`pg_dump` would copy the ciphertext, so use `backup.sh`.

Open one offline:

```sh
curl --fail-with-body "$CENSUS_URL/ops/quarantine/$ITEM_ID" -H "Authorization: Bearer $OPERATOR_TOKEN" > item.json
java deploy/tools/QuarantineKey.java open ~/dashbuddy/secrets/census-quarantine.key item.json > artifact.json
```

`artifact.json` is exactly what the phone sent: the reviewed skeleton (hashes, never screen text), its confirmations,
role tags and any gated words. Work on it on the workstation only; it never goes into a ticket or a pull request. Then
either delete the item, or approve a sanitized copy: one skeleton item with anything doubtful removed, which the server
validates like an upload and stores as a cluster sample with no sighting behind it, deleting the item in the same
transaction.

```sh
curl --fail-with-body -X POST "$CENSUS_URL/ops/quarantine/$ITEM_ID/delete" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP"

curl --fail-with-body "$CENSUS_URL/ops/quarantine/$ITEM_ID/approve" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' --data-binary @sanitized-skeleton.json
```

Delete `item.json` and `artifact.json` when done.

### Grants

`GET /ops/contributors` lists grants (id, days, whether live, today's read items from the audit log). Revoke one when
a contributor's confirmations, role tags or reads look wrong; that also deletes the install's confirmations and role
tags, and its new grant requests are refused until the revocation is cleared:

```sh
curl --fail-with-body "$CENSUS_URL/ops/contributors/$GRANT_ID/revoke" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' -d '{"revoked":true}'
```

`{"revoked":false}` clears the revocation (the install may request again). Revoking the install itself
(`/ops/installs/{uuid}/revoke`) deletes all its contributor rows too. The audit log (`contributor_reads`) names grants
and clusters, never installs, and goes with the grant or after 30 days.

### Contributor words

A word that passed the gate (two independent confirmations and a slot constant at k installs, in the day's release and
live) waits on the dashboard's "Contributor words" panel and in `GET /ops/contributors/words`. Constant is a candidate,
not proof: a common first name or a local word can pass. Approve one into the working vocabulary with the resolve
route, `source` `contributor`, using the `tokenHash`, `hashDomain` and `filterRev` from the JSON list; reject it with
`"reject":true`. Either way the waiting copy is deleted. Nothing here ships a word: the shipped vocabulary still needs
`k_ship` and the bundle review.

## Anchor check (#82)

While drafting an anchor from a skeleton, the anchor check tells you whether a word you think a slot holds is what it
holds ([API](API.md#anchor-check--82)). Use it on slots the cluster page's "Slot constancy" panel shows as `constant`;
every other slot answers `no_answer` whatever you send, the same answer as a wrong word. Take the cluster key, the
variant (the panel shows only a layout prefix; `GET /ops/clusters/$FINGERPRINT` carries the full one under `slots`)
and the slot id, set `TOTP` to a fresh code, and send up to eight candidates:

```sh
curl --fail-with-body "$CENSUS_URL/ops/anchor-check" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H "X-Census-Totp: $TOTP" \
  -H 'Content-Type: application/json' \
  -d "{\"variant\":{\"fingerprint\":\"$FINGERPRINT\",\"platformAppVersion\":\"8.0\",\"hashDomain\":1,\"filterRev\":1,\"layout\":\"$LAYOUT\"},\"slot\":\"root/0/text\",\"candidates\":[\"Heads up\",\"Pick up by\"]}"
```

Candidates are restricted to Latin-script letters (accents included), spaces and basic punctuation
(`' ’ - . , : ! ? ( ) / &`). Send phrases as they read in sentence or lower case (`Pick up by`, `accept order`); a single word in any case. The
check compares a phrase only in all lower case and in sentence case, and refuses as `not_chrome` a phrase with a
capitalized word anywhere after its first (`Accept Order`, `PICK UP BY`, `Can I help`), because capitalization cannot
tell app wording from a person's name. Chrome with a later capital is confirmed another way: through contributor-approved vocabulary (the
contributor word gate, #81, then the resolve route) or with an on-phone probe rule (gig.log#1630), never here.

- `match` with a `text`: the slot holds that text, in that case. A `hasText` anchor with the candidate will match it
  (`hasText` ignores case); use `text` as the `clearText` when you resolve the slot's token in the vocabulary queue.
  Verify spacing and unusual glyphs on a phone with a probe before the rule ships: the check compares canonical
  forms, `hasText` the raw string.
- `no_answer`: the slot does not hold that word, or the slot does not answer. Nothing more can be read from it.
- `422 not_chrome`: a candidate looks like a name, an address, a code or a contact, or is a phrase with a capitalized
  word after its first (`refused` lists which). Nothing was compared. App wording that trips the filter (a label ending in a single capital, `Plan B`) cannot be checked
  here; draft it from a trusted capture or a probe instead.
- `429 budget_exhausted`: the day's 200 candidates are spent, refused ones included.

Do not use the check to learn what a varying or regional slot holds; it cannot answer there by design. Every query is
in the audit log (`GET /ops/anchor-checks`): who asked, which slot, how many candidates and the answer class. No text
is kept, not even a match's: copy the `text` from the response when you draft. When
the agent token (#84) starts calling the check, watch its query counts and refusals per day.

## Reinstall with a new signing key

The app seals its census credential with an Android Keystore key, and a reinstall that changes the app's signing key loses it. Before such a reinstall, let the census spool drain, then reset the census identity in the app's Developer options (which withdraws it), then reinstall; the new install enrols a fresh identity. If the key is lost without a reset, the phone mints a new identity on its own: trust the new one through `/ops/installs/{uuid}/trust`, then revoke the old one through `/ops/installs/{uuid}/revoke`, which also ends the old identity's `silence` alarm.

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

The database can show pseudonymous install IDs, key hashes, token hashes, structural fingerprints, day-level counts, and reviewed vocabulary. Skeleton storage must not contain screen plaintext below the promotion gate. The S5 trusted-envelope exception is described below. No storage path may retain bearer tokens, IP addresses, or device identifiers. Nonce, revocation and deletion-tombstone timestamps are explicit exceptions to date-only observations; enrolment is a day (`installs.created_day`, #79). Request logs have known route, method, status and elapsed duration, and no part of the install ID (#79); unmatched paths are redacted. Ingest and alarm lines carry counts and dimensions only. To find an install behind abuse, read the authenticated `/ops/ledger` (today's per-install prefix counters, held 7 days in the database) and `/ops/installs`; to stop it at once, use the [pause switch](#pause-uploads). Content tables name no install (#86): a sighting or health row carries an epoch pseudonym, which only the live server can recompute from an install id, and only for the two live epochs.

A refused enrolment creates no install row, so it shows in only two places: the process-local counts on `GET /ops/enrol` (by reason, reset on restart) and container stdout, as an access line `method=POST path=/v1/enroll status=422 duration_ms=<n>`. The `silence` alarm cannot see an identity that never enrolled: it watches trusted installs only.

These choices limit attribution and request reconstruction. Preserve only the minimum permitted evidence, disable affected entry points, rotate credentials where relevant, and publish the impact and recovery actions. Audit host, proxy, cloud, and log-collector settings too: their default logging could violate the intended retention promise. Keep lifecycle enforcement active whenever ingestion is enabled.

## S5 trust and capture handling

Enrollment leaves `installs.trusted` false. An operator sets that flag through the TOTP-protected `/ops/installs/{uuid}/trust` endpoint below. Trust unlocks `POST /v1/envelopes` and the trusted-install silence alarm. Every install may submit skeletons and health reports. Trust does not bypass authentication, sensitive scanning, validation, quality limits, or shared budgets. Play Integrity decoding and attestation changes are outside this slice; the existing attestation column is untouched.

A trusted envelope contains an **already-redacted `uinode.v1` capture**, including UI chrome and its capture metadata. It is not a skeleton and may contain plaintext UI strings and the capture's timestamp. The server repeats the public contract's sensitive-marker scan, rejects hits, and removes `metadata.deviceFingerprint` and `metadata.rulesetSignature` before re-serializing and storing. This scan is a backstop, not a general redactor. An envelope is paired to a cluster only when its declared top-level `fingerprint` names an EXISTING SCREEN cluster of the SAME platform; the phone uploads skeletons first (there is no retroactive pairing), and an envelope naming no cluster or an unknown one is stored with a NULL fingerprint. The envelope's `fingerprint` is DECLARED by the phone and taken on trust because the server cannot recompute an app-side skeleton, so a trusted install can attach its capture to any same-platform screen cluster; this is acceptable while trusted means the operator's own device. Logs never contain captures, payload strings, fingerprints, hashes, bodies, or secrets. A sensitive rejection WARN contains only the public contract marker name.

Retention uses UTC half-open deadlines: envelopes with `purge_after <= today`, cluster and token sightings at day+30, and per-install health at day+30 expire (#79). Health is folded first: each batch locks and recomputes the fleet rows of up to 100 (day, platform, version) keys from the per-install rows still present, then deletes those rows, in one transaction; afterwards the day exists only as fleet totals. Cluster sightings hold presence only (one row per cluster, epoch pseudonym, observation day, version, domain and revision); the dashboard's 28-day "sightings" figure counts those rows. Every relevant read excludes expired data even if physical deletion is delayed. Sweeps commit bounded batches, check cancellation, isolate failures, and continue promptly after capped runs (50 batches); overdue work never grants extra retention. Startup drains all work before listening. Runtime passes run at UTC midnight and six-hour boundaries. `/ops/lifecycle` reports deletion/rewrite counts, batches/caps/failures, overdue rows, queue count and last-success age; failure is never reported as zero work. Withdrawal synchronously erases install-keyed data and envelope-derived drafts, and recomputes the fleet health rows its per-install health still fed. Fleet rollups retain anonymous aggregate history; observation backups remain 14 days.

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

Each delivered alarm is counted by kind in process-local `AlarmStats`. The `AlarmSink` always writes WARN lines under logger `Alarm`, the system of record, containing only kind, platform, version, the constant `install_prefix=-`, and rule IDs. Alarms never name an install (#79): the line stays only because the deployed host publisher grammar expects five lines. The per-install `trips`, `silent_rule_death` and `silence` alarms still dedupe per install in process memory; `/ops/health` shows the reporting rows by epoch pseudonym, not by install (#86). The shared renderer validates the kind against the catalogue and all other fields against their token grammars; invalid values become `[redacted]`. Alarm evaluation failures after a committed health batch log one WARN `alarm_evaluation_failed class=<simple name>` under `Alarm`, without the exception message; the accepted response remains HTTP 200. Cancellation propagates. Dedupe keys are recorded only after successful sink delivery, so a throwing sink can be retried on the next evaluation.

Alarm delivery is **WARN log + spool file → host publisher → SNS email**. `ALARM_SPOOL_DIR` enables `FileSpoolAlarmSink` and must be an absolute path; unset selects `LoggingAlarmSink` only. Compose sets `/var/spool/census-alarms` on the writable `alarm-spool` named volume; the container root stays read-only. On AWS the volume binds to `/var/lib/census-data/alarms`, mode 0700 and owned by uid/gid 10001. The app logs first, then writes exactly the shared rendering to `<epochMillis>-<counter>.alarm.tmp` and atomically renames it to `.alarm`. At the default 64-file cap it drops the oldest copy first. I/O failures increment `spool_failed` in process-local `AlarmStats` and emit at most one WARN per ten minutes, `alarm_spool_failed class=<simple name>`, without the exception message or path; health admission and purge continue. Counters and warning throttling reset on restart.

On AWS, Terraform provisions `alerts_topic_arn` in SSM from the existing SNS topic; the host renderer writes optional `ALERTS_TOPIC_ARN` into `/opt/census/.env`. The app has no topic configuration. The root Python host publisher treats all spool entries as hostile: directory-relative opens with `O_NOFOLLOW` and `fstat` require regular files owned by uid 10001, one link, and at most 4 KiB. Names must be numeric `<epochMillis>-<counter>.alarm`; strict UTF-8 messages must match exactly the five ordered fields and token grammars below. Other names (including hidden and temporary files), directories, symlinks, hard links, wrong-owner/oversized files and invalid messages are rejected and removed without following links. The host retains at most the newest 256 valid files per scan, evicting the oldest overflow; concurrent writes can require another scan. The app's ordinary cap remains 64.

The publisher attempts at most 16 oldest valid files per run with a 20-second AWS CLI timeout each. A persistent root-owned 0600 token bucket at `/var/lib/census-data/alarm-publish-state.json`, outside the spool, holds at most 20 tokens and refills at **20/hour**. Every attempt consumes a token durably before publishing, even on failure. Empty-budget and run-limit deferrals remain for later runs or storage eviction. A corrupt state fails closed and needs operator repair; reboot/upgrade preserves the budget. The hardened systemd service has a five-minute timeout, `ProtectSystem=strict`, writable paths limited to spool/state/root AWS cache, `PrivateTmp=yes` and `NoNewPrivileges=yes`. The path unit watches the spool; a five-minute timer retries failures. Success deletes the entry; failure retains it. Journal output is counts only: `census-alarm-publish: published=<n> failed=<n> rejected=<n> deferred=<n>` and, when exhausted, one `census-alarm-publish: budget exhausted, deferred=<n>` line. No file content, name, exception text or AWS output is journaled. Missing/`CHANGE-ME` optional parameters are omitted; no topic makes the publisher exit silently while the app continues logging and spooling. Existing hosts need the [one-time publisher and Compose upgrade](../deploy/aws/README.md#operator-second-factor--alarm-delivery).

Alarm email arrives through the **existing confirmed SNS subscription**. Its subject is `census alarm: <kind>` and its body contains exactly `kind`, `platform`, `version`, `install_prefix`, and `rule_ids` as `name=value`, one field per line, using validated tokens. The host accepts `[redacted]` only for platform/version; the server always sends the prefix as `-` (#79), and rule IDs form a bracketed list matching the wire grammar (two to five dot-separated lowercase segments). The body never includes payload strings, captures, full install IDs, fingerprints, hashes, bearer tokens, TOTP codes/secrets, a topic ARN/account ID, or exception messages. AWS's email envelope and unsubscribe links are outside the application body. Spool overflow or I/O failure may lose email copies; a crash after publishing but before deletion may duplicate an email. Today's delivered alarms and kind counters record local delivery, not SNS acknowledgement.

**No container holds or can obtain AWS credentials.** IMDSv2 requires tokens and uses hop limit 1; the host alone uses the instance role. The census container performs no IMDS or SNS calls. The host's existing IAM grant remains scoped to `sns:Publish` on the alarm topic.

Deduplication is in memory by `(kind, full install identity when applicable, platform, version, rule)` for the current UTC day; the full identity is never logged. For rule-list alarms only newly firing rules are delivered. The set resets on a UTC-day change. A process restart may repeat that day's alarms, which is acceptable for this delivery seam. Counters also reset on restart. A trusted install may report a failed pipeline without needing any envelope upload.

The silence clock uses a process-local map of install IDs to server receipt instants, updated on every accepted health batch, and an evaluator startup instant for installs without an entry. After a process restart the silence clock restarts: it fails toward NO alarm for the first 48 hours; the stored-day check still bounds it. No receipt timestamp is added to the database.


## S6a operator surface

`GET /ops/` is a read-only, server-rendered dashboard with identity and snapshot sections followed by alarms, seven days of health, a cluster summary table, today's ledger, installs, and the hash-free vocabulary queue.
The cluster table has one row per platform, app version and kind, with total and per-status counts. Platforms sort alphabetically, numeric versions newest first, and “Not recorded” last within each platform. A cluster observed in multiple versions contributes to each corresponding row.
Each Review link opens `GET /ops/clusters/view?platform=doordash&version=8.10&kind=screen`. This authenticated child page shows 25 clusters per page, untriaged (`new`) first, followed by `triaged`, `drafted`, `resolved`, and `ignored`; within each status, rank descends. It has status filters, Previous/Next links, and the ranking explanation. Required `platform` matches `[a-z_][a-z0-9_]{0,31}`; required `version` is a platform app version or `none` for clusters with no recorded version, including sightings recorded as `unknown`. Optional `status` uses those five statuses, and optional `page` is an integer from 1 to 2,147,483,647 (default 1, clamped to the last available page). Invalid or missing filters return an HTML 400 page. Status links reset paging.
`GET /ops/clusters/{fingerprint}/view` is the authenticated HTML cluster detail page with gated notes, a trusted-capture screen wireframe, and skeleton samples. Its only form is the logout control; it has
no JavaScript or embedded operator credential. Its CSP is
`default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'`.

For screen clusters, the **Screen wireframe** section precedes skeleton samples. It draws the newest paired, already-redacted `uinode.v1` capture from a **currently trusted, unrevoked install**, with clipped node bounds, text/description/hint/pane labels, class and view-ID titles, and highlighted clickable boxes (a node that is clickable or advertises the click action, #78). The facts line shows receipt day, the eight-character install prefix, platform app version when recorded, and drawn/visited node counts. Rendering visits at most 2,000 nodes through depth 64. When no capture is paired, the section shows an explanatory empty state; clearing trust or revoking the install removes its capture from selection immediately. Pairing requires the phone to name an existing cluster's fingerprint when uploading the envelope.

The **Slot constancy** section (#80) lists, per app version, filter revision and raw layout, every text slot with its class (`constant`, `regional`, `varying`, `withheld`, `shape`, `unknown`; [API](API.md#slot-constancy--80)), the installs that saw it within the best-supported epoch and its distinct hash count. Counts are shown below k here, as cluster counts are; a contributor's read (`POST /v1/contributor/classes`, #81) never returns a count and leaves `unknown` slots out. `constant` is candidate chrome, never proof. Cluster cards on the review page add a **Slot classes** fact: how many of the cluster's slots hold each class. The panel reads the live rows; phones never read them, only the contributor tier's released classes (#81). The lifecycle sweep reports `slots` (rows past retention) and `slot_quarantine_marks` beside the token rows' counterparts.

The wireframe uses numeric-only inline styles for frame aspect ratio and box percentages, the only inline style attributes on ops pages. The existing CSP permits them; the page still uses one constant stylesheet and no JavaScript. Labels and titles pass through the existing privacy masking and HTML escaping. The wireframe is never serialized into cluster-detail JSON; samples use the kind-specific projections described below.

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
`/ops/<first segment>` path (`clusters`, `installs`, `health`, `alarms`, `ledger`, `vocabulary`, `pause`,
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
Vocabulary hashes (keyed under the pepper, #85) are available only on the dedicated JSON vocabulary surface.

### JSON reads

| Endpoint | Result |
| --- | --- |
| `GET /ops/clusters?platform=&version=&status=&kind=&limit=` | Array of `{platformAppVersion, clusters}` groups, numeric versions newest first; optional filters must be valid when supplied. The additive `platform` filter uses `[a-z_][a-z0-9_]{0,31}` and restricts clusters before version grouping; the JSON shape is unchanged. Default 50 rows, maximum 200 across groups. Rows include dates, counts, trust/gate flags, versions, `newWithVersion`, status, resolved rule, and notes. |
| `GET /ops/clusters/{fingerprint}` | Takes the fingerprint as the cluster rows show it: a screen fingerprint is the phone's, a notification fingerprint is keyed (#85) and will not match the one the phone computed. Cluster row and `{platformAppVersion, receivedDay, skeleton}` or `{platformAppVersion, receivedDay, notification}` samples; `newWithVersion` refers to its newest observed version. `slots` (#80): per `variant` (`platformAppVersion`, `hashDomain`, `filterRev`), each slot's `slotClass`, `installs` and distinct `hashes`. |
| `GET /ops/installs?limit=` | Prefixes, dates, trust/revocation flags, last app version, and whether an attestation verdict exists. Default 50, maximum 200. |
| `GET /ops/health?days=7` | `fleet` rows and per-install rows (`pseudonymPrefix`, the first 8 hex of the row's epoch pseudonym, never the install id, #86) with day/platform/version counters; maximum 90 days. |
| `GET /ops/alarms` | Today's successfully delivered alarms and process-local `AlarmStats` counts. |
| `GET /ops/enrol` | `{"counts": {...}}`: process-local enrol outcome counts by reason (`enrolled`, `unsupported_schema`, `bad_request`, `unauthorized`, `install_exists`, `revoked`), every key present, zero included; reset on restart. Counts only, no identities. Not counted: a `413` or `408` from the body reader, and any answer given before the enrol handler runs (a routing-level `400` such as a malformed query escape, the rate limiter's `429`, a `500` or `503`). |
| `GET /ops/pause` | `{"paused":bool,"changedDay":"YYYY-MM-DD"}` (`changedDay` absent until first set). `POST` with `{"paused":bool}` and TOTP sets it; see [Pause uploads](#pause-uploads). |
| `GET /ops/ledger?day=YYYY-MM-DD` | Per-install prefix counters and totals; defaults to today. `batches` is a count, never the batch IDs. |
| `GET /ops/vocabulary/queue?limit=` | Keyed hash (`tokenHash`, the stored `HMAC(pepper, hash)`, not the phone's public census hash; #85), kind, distinct non-trusted install count (within one epoch, #86), first/last day for eligible hashes absent from vocabulary; default 50, maximum 200. |

Cluster ranking is computed at read time as `distinctInstalls28d × log2(1 + sightings28d) ×
recency`, where recency is 1.0 within 7 days, 0.5 within 28 days, and 0.1 otherwise. `sightings28d` counts presence rows (install × observation day × version), never uploads (#79). The 28-day
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

Status accepts `new`, `triaged`, `drafted`, `resolved`, or `ignored`; notes describe a stored sample, so a notes write lands only while the cluster holds one (the sweep clears notes with the last sample); notes are at most 2,000
characters and the optional resolved rule must match the wire rule-ID grammar. Set `trusted`
to false to remove trust. Successful mutations return 204; missing target rows return 404.
Revocation is idempotent and immediately blocks the install's signed requests.

Vocabulary source is `trusted`, `corpus`, `rule_anchor`, or `contributor` (a word that passed the contributor gate, #81; see [Contributor tier](#contributor-tier-81)) (#79: provenance names no install; V6 rewrote older `trusted:<prefix>` rows and a CHECK refuses that form). Clear text is at
most 128 characters. `tokenHash` is the keyed value from `GET /ops/vocabulary/queue` (#85). Resolution proves
`HMAC(pepper, CensusHash.of(clearText)) == tokenHash` (hash, key, then a constant-time compare); a mismatch, including a
phone-side public hash, returns 422 `hash_mismatch`. Rejection stores NULL clear text. Resolution requires current non-trusted
k eligibility within the supplied `hashDomain` and `filterRev` (both default 1 for older callers), seven elapsed days of quarantine and unexpired per-install evidence. It never overwrites an existing row. Sweeps remove ineligible queued/unblinded entries and expired rejected entries. `shipped` export remains M4/#641.

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

## Notification review — #1189 slice 2

Production defaults to rejecting notification schema items. The shared lifecycle prerequisites are implemented; activation requires the checks below before enabling any app publisher.

Clusters have Screen/Notification badges. Dashboard rows and Review links separate `(platform, version, kind)`. Both HTML and JSON cluster listings accept optional `kind=screen|notification`; totals and pagination use that filter, and status/paging links preserve it. The kind selector can return to all kinds. Shared ingest totals and rejection counters include both skeleton kinds and trusted envelopes; they are not notification recognition-health statistics. `/ops/health`, fleet denominators and alarm thresholds retain their existing meaning.

Notification detail shows the channel only at the live display gate (ten non-trusted installs in the current 28-day window, or the existing trusted-sighting exception); below it the channel and notes are withheld. Withdrawal or cohort decline can hide them again. All five named text fields, and a v2 notification's action labels in order, display kind badges only, never hashes or resolved body text. A shape slot shows its format template (for example `shape: $#.##`), never a value (#78). Screen samples mark nodes `clickable`, `click action` and `hidden`; the last two exist on v2 samples only. A permitted clear channel on the wire is not permission to display it below the gate.

Notification review offers **Classification** and notes using the existing screen triage-label vocabulary and `screen_class` storage. Notification rule drafting and trusted notification captures are deferred: no screen wireframe, envelope selection or draft download is offered. Direct shape/apply, preview and draft-save requests also fail with `unsupported_skeleton_kind`, even if a screen capture ID is supplied. Classification-only save continues to work, with the existing authentication, TOTP and gated-note behavior.

## Notification activation and rollback

1. Stop the old server for V5 cutover; retain a recoverable dump and latest withdrawal/floor journals. Deploy this lifecycle-capable image with `CENSUS_NOTIFICATIONS_ENABLED=false`, `CENSUS_MIN_FILTER_REV=1`. AWS uses `/census/census_notifications_enabled` and `/census/census_min_filter_rev` (substitute your name prefix). Refresh the host config renderer and **`/usr/local/sbin/census-alarm-publish` from `deploy/aws/cloud-init.yaml.tftpl`**: image deployment alone does not update the host grammar for `lifecycle_failure`, `lifecycle_backlog`, `lifecycle_stale`.
2. Startup discards unattributable legacy sightings/vocabulary and scrubs/deletes legacy samples; this resets historical k cohorts. On today's live database (one install, two batches), it deletes that install's legacy token and cluster sightings and any legacy vocabulary, scrubs valid sample hashes, removes malformed/resolved samples, clears derived notes/drafts and inseparable captures. It preserves the install/credential and the two ledger batches until their normal TTLs. Retries of those batch IDs do not rebuild evidence; fresh observations do. Independent classification is retained while its catalogue row has references.
3. Run server integration tests including Testcontainers, replay pinned corpus/notification vectors and manifest, perform an older-dump restore drill with newer floor/withdrawal journals, verify zero overdue rows and successful alarm delivery. `/ops/lifecycle` requires operator authentication and contains only activation/floors/counts/ages, never bodies, channels, fingerprints, hashes, full install IDs or exception text. Its seven-hour watchdog continues independently of sweep work.
4. Set `CENSUS_NOTIFICATIONS_ENABLED=true`, restart, and verify policy plus authenticated mixed-kind ingestion. There is no separate app switch: the phone starts publishing notifications on its next policy refresh (the hourly worker run plus the 300 s policy cache), so read `/ops/clusters?kind=notification` after a shift to confirm they arrive. Both current app builders claim filter revision 1. A floor increase rejects old positive revisions with `filter_rev_too_old`; this is a claimed-revision gate, not filter attestation.
5. To disable notifications, set the flag false and restart the SAME lifecycle-capable image. Admission changes immediately; cached policy may persist for its existing 300 seconds. Continue sweeps and preserve the monotonic floor. Never revert to pre-V5 code or restore an old database to disable notifications: obsolete code loses lifecycle enforcement and writes legacy tables.

Raising a floor: stop census, set the current deployment minimum, run `--merge-filter-floor` with the latest off-host journal, export/upload the resulting floor, then restart. Imported/configured lower revisions cannot lower the database floor. A trusted capture carries no revision of its own: once the floor is above 1, a capture whose cluster shows no sighting or sample at or above the floor (expired provenance, or never paired) is removed as unprovable. Floor journals contain only a revision and day, and must remain durable for every older restorable backup. Removing server vocabulary cannot retract a previously published bundle.
