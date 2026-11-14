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
| GET, POST | `/v1/contributor/grant` | Implemented | Request a contributor grant, or read its state (#81) |
| POST | `/v1/contributor/classes` | Implemented | Slot classes of one cluster variant, from the day's release (#81) |
| POST | `/v1/contributor/review` | Implemented | One screen's confirmations and role tags (#81) |
| POST | `/v1/contributor/gates` | Implemented | Whether the word gate is open for confirmed values (#81) |
| POST | `/v1/contributor/words` | Implemented | Plaintext words for slots whose gate is open (#81) |
| POST | `/v1/contributor/quarantine` | Implemented | "Ask the developer": the artifact, sealed to the operator (#81) |
| GET, POST | `/ops/contributors…`, `/ops/quarantine…` | Implemented | Grants, gated words and the quarantine (operator; POST needs TOTP, #81) |
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
    "hashDomain": "census.v1"
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

Withdrawal deletes `trusted_envelopes`, `health_daily`, `token_sightings_v5`, `cluster_sightings_v5`, `slot_sightings`, the contributor tier's `slot_confirmations`, `slot_roles`, `slot_release`, `quarantine_items` and `contributor_grants` (with their audit rows, #81), `ingest_ledger`, `nonces`, and `installs` in one transaction (`InstallStore.WITHDRAWAL_TABLES`; a test fails when a table that can hold the install is missing from it), and recomputes the `health_fleet_daily` rows the erased health still fed. The content tables carry epoch pseudonyms, not the install ID (#86): the server finds the install's rows by recomputing its pseudonyms under every stored epoch key. Rows under a destroyed epoch key cannot be found, by the server or anyone else, and are past retention. Shared aggregate/catalog tables are retained; health days already folded into fleet totals hold no trace of the install.

"Delete my data" (`DELETE /v1/installs/me/data`, #79) runs the same erasure in one transaction, parent row locked first, except that the `installs` row and the ledger rows from today on stay: the credential keeps working, today's quota is not reset, and a batch sent before the deletion is answered `duplicate` rather than stored again. It writes the same hashed tombstone as a withdrawal and the same off-host journal notice, and copies the tombstone instant into `installs.erased_through`; startup replay leaves a generation alone when that marker covers its tombstone. After a restore from a backup older than the deletion the marker is missing, and replay erases that generation whole (the journal does not record which kind of erasure it was), so the app re-enrols. Shared cluster samples carry no install ID: the hashes only this install sent expire at the next lifecycle pass, as do vocabulary entries it alone held at k, before `completionDeadline`. Neither request is refused by the pause switch. Deletion is synchronous; the deadline preserves a future asynchronous contract. Nonces are single-use with a 300-second validity window. HMAC signatures remain replayable within their timestamp window; S4 batch admission will use the ledger for replay protection.

Budget helpers expose a 300 accepted-skeleton default, 10 MiB, 40 distinct batches and 60 health reports per install per UTC day, all from `Policy` (`Policy.budget()`, #79), with retries at the next UTC midnight. The health-report count is checked under the ledger row lock before any byte is charged. The ledger accumulates bytes, accepted/duplicate counts and rejection counts, deduplicating stored batch IDs. `tryConsume` atomically enforces byte, accepted-item, and distinct-batch limits in one conditional PostgreSQL upsert, returning remaining allowances or seconds to UTC midnight. `recordIngest` only increments rejected/duplicate counters. Ingest calls these primitives; identity routes report budgets. The purge job starts one minute after startup and runs every six hours, deleting nonces older than one hour and ledger days strictly older than today minus seven days. Operator revocation is implemented; the remaining lifecycle gaps are documented in ARCHITECTURE.md.

## Slot constancy — #80

Each text slot of a cluster gets a class, judged across installs (ADR-0011 §14):

| Class | Meaning |
| --- | --- |
| `constant` | One word hash on at least `constantPercent` (90%) of the installs that saw the slot, and on at least k of them. Candidate chrome, never proof. |
| `regional` | At least `regionalMinValues` (2) hashes, each on at least `regionalMinInstalls` (3) installs, behind which at least `regionalPercent` (50%) of the installs stand: a store or zone name. Never confirmable. One install's values carry unrelated tags, so those installs are a lower bound (the shared hashes' installs added up, less the value rows beyond one per install); it errs toward `varying`. |
| `varying` | Anything else seen by k installs: a new hash per frame or per install, or a value the filter never hashes (`digits`, `mixed`, `words:8+`). |
| `withheld` | A backstop fired on at least `withheldPercent` (10%) of the installs. Tested before `constant`, so a slot that sometimes holds protected text is never offered as chrome. |
| `shape` | At least `shapePercent` (51%) of the installs saw a value format (#78). Never vocabulary. |
| `unknown` | Fewer than k installs saw the slot, or one hash covers the share for `constant` but on fewer than k installs. |

Support is counted per cluster variant (app version, hash domain, filter revision, raw layout) and slot, within one epoch (#86): the distinct epoch tags of non-trusted, out-of-quarantine rows. Of the live epochs, a slot is judged on the one where the most installs saw it; epochs are never added together. The thresholds are in `Policy.slotConstancy`.

The counts come from `slot_sightings` (V9), not from token sightings, which do not say where a word sat. Ingest walks each accepted, keyed skeleton and writes, per (cluster, variant, layout, slot): one presence row (`value = 'present'`) and one row per value, the keyed word hash or a marker (`withheld`, `shape`, `unhashed`). Rows are presence only and carry an epoch tag whose object is the whole row identity, value included, so one install's rows for two values carry unrelated tags; trust and the quarantine mark ride on the row as on token sightings. Each row's `last_day` is its own newest observation of that slot and value, so retention (the cluster sightings' 30 days) removes an old value on its own day. A floor raise deletes rows below it; withdrawal, data deletion and revocation delete the install's rows through its tags. At most `maxSlotsPerSkeleton` slots of one skeleton are recorded, in tree order.

Slot ids: `root/<child index>/…/<text key>` for a screen node's text field (`root/text`, `root/0/2/desc`), `windowTitle`, a notification field's wire name (`title`, `text`, `bigText`, `tickerText`, `subText`) and `action/<index>` for a notification action. The path is the tree as sent, anonymous wrappers included. A cluster's fingerprint splices anonymous wrappers away, so two raw trees of one cluster can put different nodes at one path; evidence is therefore partitioned by the **layout id**, a sha256 over the raw tree's shape (every node's class, id and child count, and for an anonymous wrapper its text keys), never its values. `SlotWalk.layout` holds its exact byte form.

**Operator views read the live rows.** The operator views below classify the live rows. A contributor's phone reads classes from the day's release instead, with its own rows left out (the contributor tier, #81, below): answered from live evidence, an install could upload a guessed value and read whether it completed a value k installs share. The thresholds (`Policy.slotConstancy`) are server configuration and are not published in `/v1/policy`.

`GET /ops/clusters/{fingerprint}` carries `slots` (each variant's slots with class, installs and distinct hashes) and the cluster page shows them in a "Slot constancy" panel; cluster cards on `/ops/clusters/view` show the count of slots per class.

## Contributor tier — #81

A contributor is an opted-in install that reviews a screen before it is sent (ADR-0011 §15, gig.log#1625–#1627). It may
confirm that a constant slot holds the app's own wording, tag what a field means, send that wording once the word gate
opens, and send a screen to the operator's quarantine. Contributor status is separate from `trusted` and never permits
`POST /v1/envelopes` (`403 not_trusted` as before). Nothing a contributor sends names the install: confirmations, role
tags and quarantine items carry epoch tags (#86), and confirmed values are keyed hashes (#85).

Every route below is authenticated like the other `/v1` routes (bearer and HMAC signature over the exact body, the
install rate limit) and takes a JSON body decoded strictly (an unknown key, or a key repeated at any depth, is `400
bad_request`). Each request that
writes checks the install and its grant in the transaction that writes. The pause switch refuses `review`, `words` and
`quarantine` like any upload (`503 paused`); grants, classes and gates are not uploads.

A **variant** names one cluster variant as the phone computed it, and is the scope of every per-slot request:

```json
{"kind":"screen","fingerprint":"<64 hex, the public fingerprint>","platformAppVersion":"8.0","hashDomain":1,"filterRev":1,"layout":"<64 hex, SlotWalk.layout>"}
```

`kind` is `screen` or `notification` (a notification's fingerprint is keyed on the server, as at ingest).
`platformAppVersion` is the skeleton's version, or `unknown` when it carried none. `hashDomain` must be accepted and
`filterRev` at or above the floor. A **slot** is a #80 slot id (`root/0/text`, `windowTitle`, `title`, `action/0`; at
most 512 characters of `[A-Za-z0-9/]`). A **hash** is the slot's public census hash (16 lowercase hex), never keyed by
the phone.

| Endpoint | Body | Success |
| --- | --- | --- |
| `POST /v1/contributor/grant` | `{"eligible":true}`: a yes or no only, no counts | `200 {"status":"granted","grantedDay":"YYYY-MM-DD","expiresDay":"YYYY-MM-DD"}`. Idempotent while the grant is live; a new grant after it expires |
| `GET /v1/contributor/grant` | none | `200 {"status":"granted"\|"expired"\|"revoked"\|"none", …days}` |
| `POST /v1/contributor/classes` | `{"variant":{…},"slots":["root/text",…]}`, the slot ids of the reader's own skeleton, 1 to 1024 | `200 {"releaseDay":"YYYY-MM-DD","slots":[{"slot":"root/text","class":"constant"}]}` |
| `POST /v1/contributor/review` | `{"variant":{…},"confirmations":[{"slot":…,"hash":…}],"roles":[{"slot":…,"role":"tip"}]}`, 1 to 256 items | `200 {"confirmations":N,"roles":N,"skipped":N}` |
| `POST /v1/contributor/gates` | `{"variant":{…},"slots":[{"slot":…,"hash":…}]}`, 1 to 64 items | `200 {"slots":[{"slot":…,"hash":…,"open":false}]}` |
| `POST /v1/contributor/words` | `{"variant":{…},"words":[{"slot":…,"hash":…,"word":"Pick up by"}]}`, 1 to 16 items | `200 {"words":[{"slot":…,"status":"accepted"}]}` |
| `POST /v1/contributor/quarantine` | `{"artifact":"<base64url of the artifact bytes>","note":"might_contain_customer"}` (note optional) | `200 {"status":"quarantined","expiresDay":"YYYY-MM-DD","artifactSha256":"<64 hex>"}` |

**Grants.** A grant is issued to an install past the server's quarantine (enrolled at least 8 UTC days ago, the
vocabulary rule) that sends `eligible: true`. It lasts 30 UTC days (`expiresDay` is the first day it no longer works).
`eligible: false` stores nothing and answers `403 not_eligible`; a younger install gets `403 install_too_new`; while the
operator's revocation stands, `403 grant_revoked`. Withdrawal deletes the install's grants; "delete my data" deletes the
live grant (a revoked one stays, so a deletion cannot lift a revocation), and the phone requests again.

**Classes** (#80's classes, released to contributors). Only `constant`, `regional`, `varying`, `withheld` and `shape`
appear; an `unknown` slot (below k) is left out, and no hash or count is ever returned. They come from the day's
release, never the live rows: once per UTC day the lifecycle sweep copies the slot rows as they stood at the end of the
previous day into `slot_release` (one statement, one transaction; a row first received today is left out and a row
written again today counts with its earlier last day), and a read is answered from that copy with the reader's own
rows left out, found through its epoch tags in the same REPEATABLE READ snapshot as the install, grant and release
checks. A release is served only on its own UTC day; until today's is built (the first sweep after midnight) the answer
is `503 release_unavailable` with `Retry-After: 3600`. An upload reaches another reader's answer the next day at the
earliest, whenever the build runs, and never moves the uploader's own. A reader is answered only about the slots it
names, and only for a variant it has itself uploaded: ownership is checked first, against the reader's own live
sightings of those slots (found by its epoch tags alone), and without one the answer is an empty list and no peer
evidence is read at all, so the work done cannot reveal whether any other install sent that variant.

**Review.** A confirmation says a slot holds the app's own wording; its hash is keyed and stored, never the word. A
role is one of `total_pay`, `tip`, `base_pay`, `deadline`, `distance`, `item_count`, `customer_info`, `store_name`,
`other`; anything else is `400`. An item is stored only when the install holds a live slot sighting of that value (a
role: of that slot), so send the skeleton through `POST /v1/skeletons` first; other items count in `skipped`.
Confirming the same value again in the same 30-day epoch changes nothing: one install counts once.

**The word gate.** `gates` answers `open: true` for a (slot, hash) only when, in one snapshot: the asker holds a live
sighting of the slot and itself confirmed that value; the slot is constant on that value, at k installs or more, both
in today's release and in the live rows, each judged with the asker's own rows left out; and at least 2 non-trusted
installs confirmed it within one epoch. The checks run in that order, both constancy checks always, and the
confirmations are read only when both hold, so a refusal below k does the same work whether or not anyone else
confirmed. A plaintext word is accepted only then. `words` answers per item: `accepted` (stored for the operator; never
auto-approved), `gate_closed`, `hash_mismatch` (the word's census hash is not `hash`), or `not_chrome` (not 1 to 8
words, no canonical form, longer than 128 characters, or a sensitive marker). A refused word is neither stored nor
logged. An accepted word waits in `contributor_words` until the operator resolves its token (`POST
/ops/vocabulary/resolve`, `source: "contributor"`) into the working vocabulary, or rejects it; the sweep also drops it
after 30 days or once fewer than 2 confirmations stand behind it.

**Quarantine.** The artifact is the outbound review as the review screen described it, a JSON object:
`{"skeleton":<one skeleton item, validated like an upload>,"confirmations":[…],"roles":[…],"words":[…]}` (the last three
optional, shaped as above). A word in it must pass the same checks and find its gate open, else `409 gate_closed` and
nothing is stored; any other invalid artifact, a key repeated at any depth included, is `422 bad_artifact`; decoded
artifacts are at most 128 KiB. The note is one of `might_contain_customer`, `might_contain_my_info`,
`unsure_what_screen`. The server never seals the bytes as received: it seals the validated artifact re-serialized in
its **canonical form**, `{"skeleton":<the contract's canonical serialization of the skeleton>,"confirmations":[…],
"roles":[…],"words":[…]}`, keys in that order, every array present, items `{"slot","hash"}`, `{"slot","role"}`,
`{"slot","hash","word"}`, compact JSON; the response's `artifactSha256` is the SHA-256 of exactly those bytes. A phone
that sends the canonical form gets back the hash of its own bytes, so the quarantined copy is byte-identical to what it
sent. It seals them to the operator's public key (`CENSUS_OPERATOR_PUBLIC_KEY`, [QuarantineCrypto](../server/src/main/kotlin/cloud/trotter/census/server/QuarantineCrypto.kt))
and can never read them back; without that key it answers `503 quarantine_unconfigured`. The item expires at the start
of `expiresDay` (3 UTC days after receipt, at most 72 hours) and is never clustered, counted, drafted from or backed up.

**Errors and limits.** `401 unauthorized` for a stale credential; `403 grant_required` without a live grant; `429
budget_exhausted` with `Retry-After` to UTC midnight when a daily quota is spent. Per install and UTC day, on its
ledger row (so a data deletion and a new grant do not reset them): 200 class and gate items (`dailyReads`), 10 reviews,
30 words, 3 quarantine items (`Policy.contributor`, server configuration, not published in `/v1/policy`). Every class,
gate and word request writes an audit row: the grant id, the day, the kind, the cluster key, the layout and the item
count; it goes with the grant or after 30 days. Logs carry counts and outcomes only.

**What the classes and the gate reveal.** Both are an equality oracle restricted to values constant across at least k
installs: a class answers `constant` and a gate opens only for a value one hash holds on at least 90% of at least k
installs in that slot (with the asker left out), which is the delivery app's own wording, shown on every dasher's
screen. Varying and regional slots never answer with a value, and customer and name values are withheld on the phone
before hashing. See [Security](SECURITY.md#contributor-tier-81) for what an attacker can and cannot learn.

Operator routes (bearer or session; every POST also needs `X-Census-Totp`):

| Endpoint | Purpose |
| --- | --- |
| `GET /ops/contributors` | Grants: `grantId`, days, `live`, `readsToday` from the audit log |
| `POST /ops/contributors/{grantId}/revoke` | `{"revoked":true}` revokes the grant and every live grant of its install and deletes the install's confirmations and role tags; `{"revoked":false}` clears a revocation. `204`, or `404` |
| `GET /ops/contributors/words` | Words waiting for approval, with the `tokenHash`, `hashDomain` and `filterRev` the resolve route takes |
| `GET /ops/quarantine` | Unexpired items: `id`, `expiresDay`, `note`, `bytes` |
| `GET /ops/quarantine/view` | The quarantine page (the only page that lists items) |
| `GET /ops/quarantine/{id}` | One item with its sealed `artifact` (base64), to open offline |
| `POST /ops/quarantine/{id}/approve` | Body: the operator's sanitized copy, one skeleton item; validated like an upload and stored as a cluster sample with no sighting behind it; the item is deleted. `204`, `404`, or `422 bad_artifact` |
| `POST /ops/quarantine/{id}/delete` | No body. `204` or `404` |

The cluster page's slot table shows each slot's role tags as non-trusted installs per role (within one epoch), and the
dashboard lists waiting contributor words.

## Anchor check — #82

The anchor check answers whether a candidate word is what a constant slot holds (ADR-0011 §16). It hashes the
candidate, keys it with the pepper and compares it with the slot's stored value: a dictionary lookup, which is why it
answers only inside the limits below. It is an operator route (bearer or session, and `X-Census-Totp` like every
operator POST); the agent token (#84) will call it under its own caller and budget.

`POST /ops/anchor-check`, body:

```json
{"variant":{"fingerprint":"<64 hex, the stored cluster key>","platformAppVersion":"8.0","hashDomain":1,"filterRev":1,"layout":"<64 hex>"},
 "slot":"root/0/text","candidates":["Pick up by","Heads up"]}
```

`fingerprint` is the cluster key as `/ops/clusters` lists it (a screen's public fingerprint, a notification's keyed
one); the other variant fields and `slot` are the ones the cluster page's slot table shows. 1 to 8 candidates, each 1 to
128 characters. The body is decoded strictly; anything malformed or out of bounds is `400 bad_request` and is not
counted or audited.

Each query runs, in this order:

1. **The chrome filter**, on every candidate, before anything is hashed or read. A candidate is refused unless its
   census canonical form is 1 to 40 characters of 1 to 8 words with no digit, carries no sensitive marker, no `@` and
   no square bracket, and none of its case variants (below) has a name or address shape: a first name and an initial
   (`Brandon C`, `Jane S.`), a customer lead-in with a tail (`Deliver to Sam`, `Pickup for …`), or a street line
   (`Main St`). A refusal is an answer of its own, never "no match": `422 {"error":"not_chrome","refused":[<indexes>]}`,
   and no candidate of that query is compared.
2. **The budget.** Each caller has a daily budget of candidates per UTC day (`Policy.anchorCheck`: 200 for the
   operator), refused ones included. Over it: `429 {"error":"budget_exhausted"}` with `Retry-After` to the next UTC
   midnight.
3. **The slot.** Only a slot classified `constant` (#80, from the live rows, every counted row included) answers: one
   hash on 90% of the installs that saw it and on at least k of them. `unknown` (below k), `regional`, `varying`,
   `withheld` and `shape` slots, and a cluster or slot nobody uploaded, answer exactly as a constant slot that holds
   none of the candidates does, after the same statements and the same hashing.
4. **The compare.** For each candidate, each case variant the app's `hasText` accepts (`hasText` compares
   case-insensitively, the census hash keeps case): as sent, all lower case, all upper case, sentence case and title
   case, each mapped one character to one character. Each variant is hashed the phone's way (`CensusHash.of`), keyed
   (`HMAC(pepper, …)`, #85) and compared in constant time with the slot's value.

`200 {"results":[{"answer":"match","text":"Pick up by"},{"answer":"no_answer"}]}`, one result per candidate in order.
`text` is the case variant the slot holds, the clear text the vocabulary resolve route needs. A match means the slot's
canonical text equals that variant; `hasText` compares the node's raw string, so a slot whose raw text differs in
spacing or in a folded glyph (a no-break space, a fullwidth letter) still needs a probe (ADR-0011 §17) before a rule
relies on it. Exact text only: "contains" and pattern candidates are answered on phones by probes, never here.

Every query, refused and over-budget ones included, writes one audit row (`anchor_checks`): caller, UTC day, the
variant, slot, the number of candidates and the answer class (`match`, `no_answer`, `refused`, `budget_exhausted`),
never which class the slot had. A query that passed the filter also stores its candidates' canonical text (operator-
chosen app wording, kept so the audit shows what was guessed); a refused or over-budget one stores no text. Rows keep
30 days, budget rows 7. Logs carry the candidate count and the answer class only.

`GET /ops/anchor-checks?limit=N` (1 to 200, default 50) lists the audit rows, newest first:
`[{"caller":"operator","day":"YYYY-MM-DD","fingerprint":…,"variant":{…},"slot":…,"candidates":2,"result":"match","words":[…]}]`.

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
