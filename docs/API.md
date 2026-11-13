# API

Public health/policy, identity, ingest and operator routes are implemented. Notification admission remains disabled by the default production policy pending the lifecycle prerequisite below.

| Method | Path | Status | Purpose |
| --- | --- | --- | --- |
| GET | `/healthz` | Implemented | Process liveness, no database |
| GET | `/readyz` | Implemented | PostgreSQL readiness |
| GET | `/v1/policy` | Implemented | Public policy and build identity |
| POST | `/v1/enroll` | Implemented | Create or confirm an installation |
| POST | `/v1/rotate` | Implemented | Replace the install secret |
| DELETE | `/v1/installs/me` | Implemented | Withdraw the install and all its keyed rows |
| DELETE | `/v1/installs/me/data` | Implemented | Delete the install's server data without withdrawing (#79) |
| POST | `/v1/nonce` | Implemented | Issue a challenge bound to an install |
| GET | `/v1/me` | Implemented | Day-level identity and remaining budgets |
| POST | `/v1/skeletons` | Implemented | Admit skeleton batches under the advertised policy |
| POST | `/v1/health` | Implemented | Daily recognition-health reports |
| POST | `/v1/slot-classes` | Implemented | Each slot's class for a cluster variant, for the contributor review screen (#80) |
| GET, POST | `/ops/pause` | Implemented | Read or set the upload pause switch (operator; POST needs TOTP) |
| GET | `/ops/` | Implemented | Authenticated operator dashboard |

`/healthz` returns `200 {"status":"ok"}`. `/readyz` returns the same when its bounded database probe succeeds, otherwise `503 {"status":"db_unavailable"}`. No database error details are exposed.

`/v1/policy` sets `Cache-Control: public, max-age=300`. Defaults:

```json
{
    "serverVersion": "dev",
    "k": 10,
    "quarantineDays": 7,
    "activeWindowDays": 30,
    "dailySkeletonBudget": 300,
    "dailyBytesBudget": 10485760,
    "dailyBatchBudget": 40,
    "dailyHealthReportBudget": 60,
    "maxSkeletonBytes": 65536,
    "maxBatchItems": 100,
    "maxBatchBytes": 1048576,
    "acceptedSchemaIds": ["uinode.skeleton.v1", "uinode.skeleton.v2"],
    "retention": {
        "tokenSightingsDays": 30,
        "clusterSightingsDays": 30,
        "trustedEnvelopesDays": 30,
        "healthDailyDays": 30,
        "ingestLedgerDays": 7,
        "backupsDays": 14,
        "withdrawalsDays": 60
    },
    "hashDomain": "census.v1",
    "slotConstancy": {
        "withheldPercent": 10,
        "shapePercent": 51,
        "constantPercent": 90,
        "regionalMinInstalls": 3,
        "regionalMinValues": 2,
        "regionalPercent": 50,
        "maxSlotsPerSkeleton": 1024
    }
}
```

`imageDigest` is included when configured and omitted when absent (`explicitNulls = false`). Defaults are encoded. Policy is a data class; `POLICY_*` overrides are planned. `hashDomain` currently uses the documented fallback pending inspection of the sibling contract.

Typed JSON decoding rejects unknown keys. Errors use `{"error":"<code>"}` without exception text. Authentication, headers, canonical HMAC bytes, and a worked signing vector are specified in [CLIENT.md](CLIENT.md). No part of the install ID is logged, and `X-Install-Id-Prefix` is ignored (#79). IDs, bodies, credentials, IPs, arbitrary paths, and query strings are never logged. Exception diagnostics retain only the exception class-name chain and the outermost exception’s first three frames (`class.method:line`); exception messages and suppressed exceptions are never rendered.

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /v1/enroll` | Bearer; JSON `{"installId":"<UUID v4>","appVersion":"1.2.3","schemaIds":["uinode.skeleton.v1"]}`; no signature required | `200`: the policy object above plus `"installIdPrefix":"<8 hex>"`; same key is idempotent |
| `POST /v1/rotate` | Bearer + HMAC with old key; JSON `{"newSecret":"<base64url>"}` | `204`, empty body; new key takes effect immediately |
| `DELETE /v1/installs/me` | Bearer + HMAC; empty body | `202 {"status":"withdrawn","completionDeadline":"<tomorrow UTC YYYY-MM-DD>"}` |
| `DELETE /v1/installs/me/data` | Bearer + HMAC; empty body | `200 {"status":"deleted","rows":<erased rows>,"deletedDay":"<today UTC>","completionDeadline":"<tomorrow UTC>"}`; the credential stays valid |
| `POST /v1/nonce` | Bearer + HMAC; empty body | `200 {"nonce":"<32 lowercase hex>","expiresInSeconds":300}` |
| `GET /v1/me` | Bearer + HMAC; empty body | `200 {"installIdPrefix":"<8 hex>","createdDay":"YYYY-MM-DD","lastSeenDay":"YYYY-MM-DD","trusted":false,"budget":{"skeletonsRemainingToday":300,"bytesRemainingToday":10485760,"resetInSeconds":43200}}` (example remaining values) |

The enrollment body ID must equal the bearer's canonical lowercase UUID v4. `appVersion` is 1–64 characters from `[A-Za-z0-9+._-]`. `schemaIds` lists the schemas the client can produce. The server enrolls when at least one of them is in the policy's `acceptedSchemaIds` and ignores the rest; an empty list, or one with no accepted ID, is `422 unsupported_schema`. The list holds at most 8 IDs, each 1–64 characters from `[a-z0-9_.-]`; past those bounds it is `400 bad_request`. Nothing about schemas is stored at enrollment, and uploads are still checked item by item, so an ID the policy does not accept grants nothing. Clients learn what is accepted from `GET /v1/policy` and from the policy returned by enrollment (DashBuddy #1522). Secrets encode at least 32 random bytes as canonical unpadded base64url, at most 128 characters. Request bodies are capped at 1 MiB, including bodies without Content-Length.

| Applies to | Status | Error |
| --- | --- | --- |
| All five identity endpoints, database not wired | `503` | `db_unavailable` |
| All identity endpoints, malformed/missing bearer, ID mismatch, unknown ID or wrong secret | `401` | `unauthorized` |
| Authenticated endpoints, missing/invalid signature or timestamp outside ±300 seconds | `401` | `unauthorized` |
| Enrollment or authenticated endpoints, matching revoked credential (enrollment rejects any reuse of a revoked ID) | `401` | `revoked` |
| Enrollment, existing ID with another key | `409` | `install_exists` |
| Enrollment, no requested schema ID is accepted by policy (including an empty list) | `422` | `unsupported_schema` |
| Enrollment/rotation, invalid JSON, unknown JSON key, invalid app version or new secret, more than 8 schema IDs or a malformed schema ID | `400` | `bad_request` |
| All identity endpoints, oversized body | `413` | `payload_too_large` |
| All identity endpoints, exhausted rate bucket | `429` | `rate_limited`, with `Retry-After` seconds |
| Skeleton or health upload, per-install daily quota spent | `429` | `budget_exhausted`, with `Retry-After` to UTC midnight |
| Skeleton or health upload from a non-trusted install while paused | `503` | `paused`, with `Retry-After: 3600` |
| Unexpected failures | `500` | `internal_error` |

Before authentication reads a body or accesses the database, a global token bucket (capacity 600, refill 600/minute, configurable through `AdmissionLimiter`) runs ahead of per-ID buckets (capacity 120, refill 120/minute). Each attempt reaching the global bucket spends a token even if its per-ID check fails. A shared semaphore allows at most 32 in-flight authentication decisions, with zero wait for a permit and release in `finally`. Exhausted buckets or permits return `429 rate_limited` with `Retry-After: 1`.

The synchronized access-order LRU holds at most 10,000 unverified install IDs. On reaching capacity it evicts only the least-recently-used bucket whose refilled balance is at least half capacity; otherwise it denies the new ID and increments `capacityDenials`. Exhausted IDs therefore keep their debt under churn. An attacker who knows a victim's ID can starve its 120/minute admission allowance. This fairness trade-off is accepted because IDs are 128-bit random values and the authenticated per-install quota is separate. No IP is used as a key.

Subsequent rate limits use Ktor's [rate-limit plugin](https://ktor.io/docs/server-rate-limit.html): enrollment is globally shared at 120/hour per server process; authenticated routes share 60/minute per install; nonce issuance additionally permits 30/hour per install. Authenticated Ktor buckets use verified install IDs, never client IPs. A per-IP limiter runs at the Caddy edge in front of these (`/v1/enroll` 10/min and 20/h, all of `/v1` 200/min per client address, #1178). Buckets are process-local and reset on restart.

Rotation and withdrawal revalidate the current credential within their own transaction. Rotation conditionally updates the current hash; withdrawal locks and checks the parent before deleting anything. Stale credentials receive `401 unauthorized`, including after a concurrent rotation or withdrawal and re-enrollment of the same UUID. A matching revoked credential receives `401 revoked` at authentication.

Authentication captures one instant in `RequestInstantKey` for admission, the signature timestamp window, the last-seen UTC day, and the route's ledger day and `resetInSeconds`. Routes fall back to the clock only when the attribute is absent. Last-seen updates are monotonic, bound to the verified credential, and exclude revoked rows; `GET /v1/me` re-reads the stored row instead of echoing the attempted day. Counters and reset describe the same UTC day even at midnight.

Withdrawal deletes `trusted_envelopes`, `health_daily`, `token_sightings_v5`, `cluster_sightings_v5`, `slot_sightings`, `ingest_ledger`, `nonces`, and `installs` in one transaction (`InstallStore.WITHDRAWAL_TABLES`; a test fails when a table that can hold the install is missing from it), and recomputes the `health_fleet_daily` rows the erased health still fed. The four content tables carry epoch pseudonyms, not the install ID (#86): the server finds the install's rows by recomputing its pseudonyms under every stored epoch key. Rows under a destroyed epoch key cannot be found, by the server or anyone else, and are past retention. Shared aggregate/catalog tables are retained; health days already folded into fleet totals hold no trace of the install.

"Delete my data" (`DELETE /v1/installs/me/data`, #79) runs the same erasure in one transaction, parent row locked first, except that the `installs` row and the ledger rows from today on stay: the credential keeps working, today's quota is not reset, and a batch sent before the deletion is answered `duplicate` rather than stored again. It writes the same hashed tombstone as a withdrawal and the same off-host journal notice, and copies the tombstone instant into `installs.erased_through`; startup replay leaves a generation alone when that marker covers its tombstone. After a restore from a backup older than the deletion the marker is missing, and replay erases that generation whole (the journal does not record which kind of erasure it was), so the app re-enrols. Shared cluster samples carry no install ID: the hashes only this install sent expire at the next lifecycle pass, as do vocabulary entries it alone held at k, before `completionDeadline`. Neither request is refused by the pause switch. Deletion is synchronous; the deadline preserves a future asynchronous contract. Nonces are single-use with a 300-second validity window. HMAC signatures remain replayable within their timestamp window; S4 batch admission will use the ledger for replay protection.

Budget helpers expose a 300 accepted-skeleton default, 10 MiB, 40 distinct batches and 60 health reports per install per UTC day, all from `Policy` (`Policy.budget()`, #79), with retries at the next UTC midnight. The health-report count is checked under the ledger row lock before any byte is charged. The ledger accumulates bytes, accepted/duplicate counts and rejection counts, deduplicating stored batch IDs. `tryConsume` atomically enforces byte, accepted-item, and distinct-batch limits in one conditional PostgreSQL upsert, returning remaining allowances or seconds to UTC midnight. `recordIngest` only increments rejected/duplicate counters. Ingest calls these primitives; identity routes report budgets. The purge job starts one minute after startup and runs every six hours, deleting nonces older than one hour and ledger days strictly older than today minus seven days. Operator revocation is implemented; the remaining lifecycle gaps are documented in ARCHITECTURE.md.

## Slot constancy — #80

Each text slot of a cluster gets a class, judged across installs (ADR-0011 §14):

| Class | Meaning |
| --- | --- |
| `constant` | One word hash on at least `constantPercent` (90%) of the installs that saw the slot, and on at least k of them. Candidate chrome, never proof. |
| `regional` | At least `regionalMinValues` (2) hashes, each on at least `regionalMinInstalls` (3) installs, together covering `regionalPercent` (50%) of the installs: a store or zone name. Never confirmable. |
| `varying` | Anything else seen by k installs: a new hash per frame or per install, or a value the filter never hashes (`digits`, `mixed`, `words:8+`). |
| `withheld` | A backstop fired on at least `withheldPercent` (10%) of the installs. Tested before `constant`, so a slot that sometimes holds protected text is never offered as chrome. |
| `shape` | At least `shapePercent` (51%) of the installs saw a value format (#78). Never vocabulary. |
| `unknown` | Fewer than k installs saw the slot, or one hash covers the share for `constant` but on fewer than k installs. |

Support is counted per cluster variant (app version, hash domain, filter revision) and slot, within one epoch (#86): the distinct epoch tags of non-trusted, out-of-quarantine rows. Of the live epochs, a slot is judged on the one where the most installs saw it; epochs are never added together. The thresholds are in `Policy.slotConstancy`, published under `/v1/policy`.

The counts come from `slot_sightings` (V9), not from token sightings, which do not say where a word sat. Ingest walks each accepted, keyed skeleton and writes, per (cluster, variant, slot): one presence row (`value = 'present'`) and one row per value, the keyed word hash or a marker (`withheld`, `shape`, `unhashed`). Rows are presence only and carry an epoch tag whose object is the whole row identity, value included, so one install's rows for two values carry unrelated tags; trust and the quarantine mark ride on the row as on token sightings. Retention is the cluster sightings' 30 days on `last_day`; a floor raise deletes rows below it; withdrawal, data deletion and revocation delete the install's rows through its tags. At most `maxSlotsPerSkeleton` slots of one skeleton are recorded, in tree order.

Slot ids, which the app computes from its own skeleton ([CLIENT.md](CLIENT.md#reading-slot-classes)): `root/<child index>/…/<text key>` for a screen node's text field (`root/text`, `root/0/2/desc`), `windowTitle`, a notification field's wire name (`title`, `text`, `bigText`, `tickerText`, `subText`) and `action/<index>` for a notification action. The path is the tree as sent, anonymous wrappers included.

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /v1/slot-classes` | Bearer + HMAC; JSON `{"items":[{"kind":"screen","fingerprint":"<64 hex, public>","platformAppVersion":"8.0","hashDomain":1,"filterRev":1}]}`, 1–8 items | `200 {"items":[{"fingerprint":"<as sent>","slots":{"root/text":"constant","root/0/text":"varying"}}]}` |

The answer holds classes only: never a hash, keyed or public, and never a count. A slot below k is left out of `slots`, so a cluster fewer than k installs saw answers `{}`, exactly as one nobody saw; the app treats a missing slot as `unknown`. A hash domain the policy does not accept, or a revision below its floor, also answers `{}`. `platformAppVersion` may be null (the server's `unknown` variant). Unknown keys, an empty or over-long list, a malformed fingerprint, kind, version or counter are `400 bad_request`. The route shares the authenticated install rate limit, is never paused, and writes nothing. The body is signed, so the fingerprints asked about appear in no path or log line.

`GET /ops/clusters/{fingerprint}` carries `slots` (each variant's slots with class, installs and distinct hashes) and the cluster page shows them in a "Slot constancy" panel; cluster cards on `/ops/clusters/view` show the count of slots per class.

## Skeleton v2: value shapes, click flags, notification actions — #78

The policy lists `uinode.skeleton.v2` beside `uinode.skeleton.v1`, and, while notifications are enabled, `notification.skeleton.v2` beside `notification.skeleton.v1`. The order is the contract's: oldest version first, each version's screen id before its notification id. The app sends the newest version the list names, so a server without v2 keeps receiving v1, and v1 items from older apps stay accepted. The server lists only the versions it validates (`SERVED_SKELETON_VERSIONS`), never every version a newer contract pin defines.

A v2 item may carry, beyond the v1 keys:

- on a node, the flags `hasClickAction` (the node advertises the click action, as Compose controls do without `isClickable`) and `isVisibleToUser`, both JSON booleans;
- on any text slot, kind `shape` with a `shape` string: a value's format with its digits removed, one of the contract's closed `ShapeTemplate` list (`$#.##`, `#:## AM/PM`, `#.# mi`, `# min(s)`, `# item(s)` and the rest). A shape slot carries no hash;
- on a notification, `actions`: the action labels in order, at most 8, each a text slot judged like the five text slots (a hash, a coarse kind, a shape or `withheld`), never a label in clear.

A shape outside the list, a `shape` key on a slot whose kind is not `shape`, or kind `shape` without one is rejected as `bad_shape`. A v1 item carrying any v2 key, whatever its value, is `unknown_field`, as any unknown key on a v1 item always was; the contract's strict v1 decoder refuses the same item. Kind `shape` on a v1 slot stays `bad_kind`.

Fingerprints ignore the v2 additions: a screen's covers class, id and structure only, and a notification's omits the actions and reads a shape slot as `mixed`, the kind v1 sent for the same value. An install moving from v1 to v2 therefore keeps its clusters; a cluster can hold v1 and v2 samples side by side. Action-label hashes are tokens like any slot hash: they count toward k and the vocabulary queue, expire from stored samples on the token deadline, and are keyed under the pepper before storage (#85). Shape slots hold no hash and are stored as sent; shapes are not vocabulary.

Operator projections show a shape slot as `shape:<template>` in `kinds` (a stored template off the list reads plain `shape`), the node flags as `clickable`, `clickAction` and `hidden`, and a notification's `actions` as kind badges. v1 samples carry only `clickable`. The trusted-envelope wireframe draws a node as clickable when it is clickable or advertises the click action, as the authoring walk does.

## Notification skeletons — #1189 slice 2

`POST /v1/skeletons` supports mixed screen/notification batches under an explicitly supplied policy accepting both contract schema IDs (`uinode.skeleton.v1`, `notification.skeleton.v1`). **The default production policy still advertises only screens**: notification activation requires the separate lifecycle prerequisite described in ARCHITECTURE.md. Do not enable the app notification publisher yet.

Notification items use the pinned contract's `kind="notification"`, `channelId`, and exactly five `slots`: `title`, `text`, `bigText`, `tickerText`, `subText`. Each slot is a contract TextSlot (kind and, where permitted, hash), never clear text. The channel grammar is `[A-Za-z0-9_.-]{1,64}`. The server recomputes the whole-item fingerprint, whose notification identity includes platform, channel, and slot kinds/hashes. It stores neither the slot hashes nor that fingerprint as received: both are keyed under the server pepper first (#85), so operator surfaces show a keyed notification fingerprint. Screen text-key policy, metadata/date validation, byte limits, authentication and consent are unchanged; `expired` is invalid inbound. Added reason codes are `unknown_skeleton_kind`, `kind_schema_mismatch`, and `bad_channel`; `bad_kind` still means a TextSlot kind. Reasons are enumerated and submitted values never enter rejection messages or logs.

The single `(install_id, day)` ledger allows **300 accepted items per UTC day across both kinds together**, including the existing trusted-envelope budget consumption. The batch limit remains 100 items; existing byte and batch budgets apply jointly. Accepted items consume quota before within-batch fingerprint deduplication; a known batch ID does not spend again. Rejection counters remain in batch responses and `/ops/ledger`.

`GET /ops/clusters` and `GET /ops/clusters/view` accept optional `kind=screen|notification`; invalid kinds return 400. Omission includes both kinds. Cluster rows include `kind`; dashboard summaries group by platform, app version and kind. Samples contain exactly one of `skeleton` or `notification`, with the absent property omitted. Notification projections contain `channelId` only when unblinded, `channelWithheld`, and a five-field `slots` map containing kind badges only. No slot hashes or resolved notification text are returned at any gate level.

Notification classification uses the existing `screenClass` API property and enumerated screen triage labels. Form POST `/ops/clusters/{fingerprint}/draft` with `mode=classify` saves classification/notes. Other notification draft-save, shape/apply and preview operations return 400 `unsupported_skeleton_kind`; draft download returns 404. Trusted `uinode.v1` envelopes pair only with screen clusters of the same platform. Notification trusted envelopes, rule generation, and recognition-health denominators are deferred.
