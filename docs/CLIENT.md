# Census installer and uploader protocol

Use HTTPS. Generate one canonical lowercase UUID v4 (`UUID.randomUUID().toString()`) per installation and a secret from at least 32 cryptographically random bytes. Encode those bytes as canonical base64url without `=` padding; the encoded secret must be at most 128 characters. Never derive credentials from device identifiers. Keep the secret in encrypted app-private storage wrapped by an Android Keystore key, exclude it from backups, and never put credentials, signatures, bodies, or full install IDs in logs or crash reports.

Enroll with `POST /v1/enroll`, `Content-Type: application/json`, and `Authorization: Bearer <installId>.<secret>`. For example, the JSON has `installId`, `appVersion` (`1.2.3+42`, at most 64 allowed ASCII characters), and `schemaIds` (`["uinode.skeleton.v1"]`). Enrollment does not require an HMAC. A successful response is the complete policy plus `installIdPrefix`; retain policy limits. Repeating the same ID and secret is idempotent. A conflicting secret returns `409 install_exists`; do not silently replace an existing installation's credentials.

Every request to an authenticated route includes the bearer plus:

| Header | Value |
| --- | --- |
| `X-Census-Timestamp` | Unix seconds as a decimal integer, with no `+`, leading zeroes, whitespace, or fractions |
| `X-Census-Signature` | `v1=` followed by 64 lowercase hex characters |

The shared `RequestSigner` object defines the byte-exact algorithm. Construct this string, with exactly three single LF bytes (`0a`), no CR bytes, and **no trailing newline**:

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + SHA256_HEX(RAW_BODY)
```

`METHOD` is the actual uppercase HTTP method. `PATH` is the URL path as transmitted (preserve percent escapes and any trailing slash), without scheme, host, query, or fragment. Query parameters are not authenticated and must not carry authenticated decisions. `TIMESTAMP` is exactly the header value. `RAW_BODY` is exactly the transmitted byte sequence, including JSON whitespace and property order; for an absent body use zero bytes. Hash the raw bytes with SHA-256 and render 64 lowercase hex characters. Encode the canonical string as UTF-8. HMAC-SHA256 uses the **base64url-decoded secret bytes** as its key. Prefix the lowercase hex result with `v1=`. The stored credential hash, separately, is SHA-256 of the UTF-8 **encoded secret string**.

The server accepts timestamps within ±300 whole seconds of its UTC clock, inclusive. Synchronize the device clock; a rejected timestamp and a bad signature both return `401 unauthorized`. An identical signed request can be replayed within that window. HMAC authenticates bytes, not uniqueness. Skeleton ingest enforces replay protection using the per-install, per-UTC-day `batchId` ledger; callers must use a stable batch ID when retrying the same batch.

Worked vector (also asserted by `auth/RequestSignerTest`): the key bytes are `00 01 02 ... 1f`, encoded as:

```text
AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
```

For `POST /v1/nonce`, an empty body, and timestamp `1790899200`, the canonical string is the following four lines, with no newline after the final `5`:

```text
POST
/v1/nonce
1790899200
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

The resulting header is:

```text
X-Census-Signature: v1=bcf991bb04da013f724bd200abe7e8352c68dbd7155aacbcb6a1a1dff94cbefd
```

This public fixture is for testing only. It must not be used as a real credential.

`POST /v1/rotate` takes `{"newSecret":"<fresh encoded secret>"}` and must be signed with the **old** secret over the exact new-secret body. A `204` response means the old key no longer authenticates subsequent requests. Rotation and withdrawal revalidate the current credential inside their own database transaction; a stale secret receives `401 unauthorized`, including when another request rotated the key or withdrew and re-enrolled the same UUID after authentication. Persist the new secret securely before discarding the old one. If the response is lost, try a signed `GET /v1/me` using the pending new key to resolve whether rotation completed. A leaked bearer includes the signing key and must be treated as full credential compromise; see [SECURITY.md](SECURITY.md).

`POST /v1/nonce` with an empty signed body returns a 32-hex nonce and `expiresInSeconds: 300`. The nonce is bound to the install and can be consumed once. It is distinct from HMAC timestamp validation.

`GET /v1/me` provides only the install prefix, UTC creation/last-seen days, trust flag, and today's remaining skeleton/byte budgets and seconds to reset. The ledger day and `resetInSeconds` are derived from one captured instant, so the counters and reset remain consistent across UTC midnight. The install row and its ledger row are read in ONE statement filtered on the authenticated credential, so a credential that was withdrawn and whose UUID was re-enrolled never sees the replacement generation's dates or budget (it receives `401 unauthorized`). Accepted skeletons consume the skeleton allowance; ledger bytes consume the byte allowance. Defaults are 300 skeletons, 10 MiB, and 40 distinct batches per UTC day. The atomic `tryConsume` ledger operation enforces all three limits; the ingest handler uses it for accepted work. Rejections and duplicates have separate counters.

Withdraw using a signed `DELETE /v1/installs/me` with no body. `202 {"status":"withdrawn","completionDeadline":"YYYY-MM-DD"}` confirms synchronous deletion of the install and its keyed rows. The deadline is tomorrow's UTC date and reserves a future asynchronous contract. After confirmation, erase local credentials and pending uploads. A lost response can be checked with `GET /v1/me`: `401 unauthorized` means the old credential no longer authenticates, though it does not by itself prove the reason. Revoked credentials receive `401 revoked`; cease uploads.

Honor `Retry-After` on `429 rate_limited`. Before timestamp checks, body reads, or database I/O, authenticated routes apply an admission token bucket keyed by the bearer’s unverified install ID string: capacity 120, refilling at 120/minute. Exhaustion returns `429 rate_limited` with `Retry-After: 1`. The synchronized LRU retains at most 10,000 IDs and never uses IP addresses. Enrollment shares one server-process bucket of 120/hour; authenticated traffic shares 60/minute per install, with an additional 30/hour per install for nonces. These in-memory buckets reset on restart. The per-IP edge limit is deferred to the custom-Caddy slice; the application never obtains, stores, or logs an IP address.

Server route authors: `InstallAuth` consumes the request channel once, with a 1 MiB limit, and places the verified raw bytes in `AuthenticatedBodyKey`. Decode that attribute; do not call `receive()` or `receiveChannel()` again. The verified identity is in `AuthenticatedInstallKey`. Only its eight-character prefix may appear in logs.

## Uploading skeletons

Send a signed `POST /v1/skeletons` with `Content-Type: application/json`. Sign the exact transmitted bytes, including whitespace. The request is:

```json
{
  "batchId": "upload-1",
  "items": [{
    "schemaId": "uinode.skeleton.v1",
    "hashDomain": 1,
    "filterRev": 1,
    "fingerprint": "<CensusFingerprint.of(root)>",
    "platform": "doordash",
    "engineVersion": 1,
    "day": "2026-10-02",
    "root": {"class": "android.widget.TextView"}
  }]
}
```

Each item must be the contract's `UiSkeletonDto` JSON object, with hashed text slots and a structural fingerprint. Replace the example's fingerprint placeholder with the contract's computed value and use the capture's day. `batchId` is 1–64 characters from `[A-Za-z0-9_-]`. Send 1–100 items, at most 1 MiB for the whole raw body. The server stores the contract's measured canonical JSON, at most 65,536 bytes per item. Trees may have at most 64 levels (root is level 1) and 4,096 nodes. Policy publishes `acceptedSchemaIds` and `acceptedHashDomains` (currently `[1]`). Item dates must lie between seven days before and one day after the server's UTC date, inclusive; sightings use the server date.

Metadata is UI chrome, never free text. The policy at `/v1/policy` publishes `acceptedPlatforms` (`doordash`, `uber`, `_unknown`) and `acceptedTextKeys` (`text`, `desc`, `state`, `pane`, `role`, `hint`, `tooltip`, `error`, `clickLabel`, `uid`). Platforms also pass the bounded grammar `[a-z_][a-z0-9_]{0,31}` before allowlist lookup. Optional version strings must match their specific grammar:

| Field | Grammar |
| --- | --- |
| `platformAppVersion` | `^[0-9]{1,5}(\.[0-9]{1,5}){0,3}$` |
| `appVersion` | `^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}(\+([0-9a-f]{7,40}(\.dirty)?\|nogit))?$`, or exactly `test` |
| `rulesetReleaseTag` | `^(corpus\|dev\|v?[0-9]{1,5}(\.[0-9]{1,5}){0,3}(-[a-z0-9]{1,12})?)$` |

The raw JSON may nest objects and arrays at most 96 levels, counted outside string literals before parsing; exceeding that limit returns `400 bad_request`.

A successful batch returns:

```json
{"status":"accepted","accepted":80,"duplicate":0,"rejected":{"bad_hash":20},"budget":{"skeletonsRemainingToday":220,"bytesRemainingToday":10465760,"batchesRemainingToday":39,"resetInSeconds":43200}}
```

Figures above are illustrative. `accepted` counts accepted items after collapsing repeated fingerprints within this batch. `duplicate` counts those collapsed occurrences; each still contributes a sighting. Sightings are counted separately for each distinct `platformAppVersion` within a fingerprint group, using `unknown` when absent. The first occurrence supplies the single sample for the group, including its version. Hashes from every accepted occurrence contribute token sightings. An existing fingerprint from an earlier batch remains an accepted sighting. Samples are capped at five per fingerprint.

Validation uses the first applicable reason in this order:

| Item reason | Meaning |
| --- | --- |
| `bad_item` | Item is not an object, a required value is missing, a value has the wrong type, `isChecked` is outside 0–2, or a remaining contract constraint fails. |
| `unknown_schema` | Missing, non-string, or unaccepted `schemaId`. |
| `unknown_field` | An item, node, text slot, or text map contains a key outside its allowlist. |
| `plaintext_field` | A JSON string occurs outside the allowed string slots, including a flag, children value, or text-map value. |
| `too_deep` | Tree exceeds 64 levels. |
| `too_many_nodes` | Tree exceeds 4,096 nodes. |
| `bad_kind` | Text slot kind is not a contract wire kind. |
| `bad_hash` | A present hash is not 16 lowercase hexadecimal characters. |
| `hash_on_withheld_kind` | A non-hashable kind carries a hash. |
| `missing_hash` | A hashable kind (`words:1` through `words:8`) lacks its hash. |
| `bad_id` | Node ID fails the wire ID grammar or string well-formedness. |
| `bad_class` | Node class fails the static class grammar or string well-formedness. |
| `hash_domain_mismatch` | `hashDomain` is not accepted by policy. |
| `bad_platform` | Platform fails the bounded grammar or is absent from policy's `acceptedPlatforms`. |
| `bad_day` | Day is not a real ISO `yyyy-MM-dd` date. |
| `stale_day` | Day is outside the accepted date window. |
| `bad_version` | An optional version string fails its field-specific grammar above. |
| `too_large` | Measured canonical skeleton exceeds the policy size cap. |
| `fingerprint_mismatch` | Declared fingerprint is missing, malformed, or differs from the recomputed structural fingerprint. |

Object/schema checks precede allowlists; types precede tree limits; slots precede node values and item scalars; decoding precedes size and fingerprint checks. `bad_item` can occur at several of those stages. Only reason counts appear in responses, never offending values.

If **strictly more than 20%** of items are rejected (`rejected * 5 > items.size`), the entire batch receives `422 {"error":"batch_quality","rejected":{"reason":count}}`. Nothing is stored or charged, rejection counts are recorded, and the batch ID remains available for a corrected retry. Exactly 20% rejected is admissible; valid items proceed and rejected items are counted.

A previously admitted batch ID for the same install and server UTC day returns `200 {"status":"duplicate","accepted":0,"duplicate":N,"rejected":{}}`, where `N` is the retry's item count. After envelope, batch-size, item-validation and batch-quality checks, the ingest transaction decides this under the ledger row lock before budget checks. The server records the duplicate count, stores no samples or sightings, and charges no additional budget. It does not compare retry contents: never reuse an admitted ID for different work. A new UTC day starts a new ID ledger.

Defaults are 300 accepted skeletons, 10 MiB of raw request bytes, and 40 distinct admitted batch IDs per install per UTC day. An admitted batch charges **all raw body bytes** and every valid item **before** collapsing repeated fingerprints. Rejected items in an admissible batch do not consume skeleton allowance, but their raw bytes are part of the byte charge. Budget exhaustion returns `429 {"error":"budget_exhausted"}` with `Retry-After` equal to seconds until UTC midnight, and charges no quota, records no batch ID or counters, and stores no samples or sightings for that batch. Response budget figures come from the quota reservation; `resetInSeconds` uses the request's captured UTC instant. Credential revalidation, duplicate detection, budget reservation, stored rows, and duplicate/rejection accounting are ONE transaction. Concurrent retries of the same admitted batch produce one accepted result and one duplicate result. A batch is recorded as admitted only if its samples and sightings were stored, so a `duplicate` answer on retry means the work landed. The transaction rechecks the authenticated credential under a shared install-row lock; a withdrawn credential's in-flight upload is refused with `401 unauthorized`, including after re-enrollment of the same install ID with a new key.

Batch-level errors are `400 bad_request` for malformed JSON/UTF-8, nesting beyond 96 levels, a non-object envelope, an invalid/missing batch ID, non-array items, or an empty array; `413 batch_too_large` for too many items; `422 batch_quality`; and `429 budget_exhausted`. Existing authentication and transport errors also apply: `401 unauthorized` or `revoked`, `408 request_timeout`, `413 payload_too_large` for the raw 1 MiB limit, and `429 rate_limited` for request throttling. An unavailable database returns `503 db_unavailable`; unexpected failures return sanitized `500 internal_error`. Honor `Retry-After` and retain a stable batch ID for retries.
