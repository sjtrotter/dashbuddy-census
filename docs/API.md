# API status — #1157 S3

The public health/policy routes and all five identity endpoints are implemented. Ingest and operator routes remain future work.

| Method | Path | Status | Purpose |
| --- | --- | --- | --- |
| GET | `/healthz` | Implemented | Process liveness, no database |
| GET | `/readyz` | Implemented | PostgreSQL readiness |
| GET | `/v1/policy` | Implemented | Public policy and build identity |
| POST | `/v1/enroll` | Implemented | Create or confirm an installation |
| POST | `/v1/rotate` | Implemented | Replace the install secret |
| DELETE | `/v1/installs/me` | Implemented | Withdraw the install and all its keyed rows |
| POST | `/v1/nonce` | Implemented | Issue a challenge bound to an install |
| GET | `/v1/me` | Implemented | Day-level identity and remaining budgets |
| POST | `/v1/ingest` | Planned, provisional path | Admit skeleton batches and health summaries |
| GET | `/operator` | Planned, provisional path | Operator dashboard |

`/healthz` returns `200 {"status":"ok"}`. `/readyz` returns the same when its bounded database probe succeeds, otherwise `503 {"status":"db_unavailable"}`. No database error details are exposed.

`/v1/policy` sets `Cache-Control: public, max-age=300`. Defaults:

```json
{
    "serverVersion": "dev",
    "k": 10,
    "quarantineDays": 7,
    "activeWindowDays": 30,
    "dailySkeletonBudget": 300,
    "maxSkeletonBytes": 65536,
    "maxBatchItems": 100,
    "maxBatchBytes": 1048576,
    "acceptedSchemaIds": ["uinode.skeleton.v1"],
    "retention": {
        "tokenSightingsDays": 30,
        "clusterSightingsDays": 90,
        "trustedEnvelopesDays": 30,
        "healthDailyDays": 180,
        "ingestLedgerDays": 7,
        "backupsDays": 14
    },
    "hashDomain": "census.v1"
}
```

`imageDigest` is included when configured and omitted when absent (`explicitNulls = false`). Defaults are encoded. Policy is a data class; `POLICY_*` overrides are planned. `hashDomain` currently uses the documented fallback pending inspection of the sibling contract.

Typed JSON decoding rejects unknown keys. Errors use `{"error":"<code>"}` without exception text. Authentication, headers, canonical HMAC bytes, and a worked signing vector are specified in [CLIENT.md](CLIENT.md). Only the prefix derived by successful authentication is logged; `X-Install-Id-Prefix` is ignored. Full IDs, bodies, credentials, IPs, arbitrary paths, and query strings are never logged.

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /v1/enroll` | Bearer; JSON `{"installId":"<UUID v4>","appVersion":"1.2.3","schemaIds":["uinode.skeleton.v1"]}`; no signature required | `200`: the policy object above plus `"installIdPrefix":"<8 hex>"`; same key is idempotent |
| `POST /v1/rotate` | Bearer + HMAC with old key; JSON `{"newSecret":"<base64url>"}` | `204`, empty body; new key takes effect immediately |
| `DELETE /v1/installs/me` | Bearer + HMAC; empty body | `202 {"status":"withdrawn","completionDeadline":"<tomorrow UTC YYYY-MM-DD>"}` |
| `POST /v1/nonce` | Bearer + HMAC; empty body | `200 {"nonce":"<32 lowercase hex>","expiresInSeconds":300}` |
| `GET /v1/me` | Bearer + HMAC; empty body | `200 {"installIdPrefix":"<8 hex>","createdDay":"YYYY-MM-DD","lastSeenDay":"YYYY-MM-DD","trusted":false,"budget":{"skeletonsRemainingToday":300,"bytesRemainingToday":10485760,"resetInSeconds":43200}}` (example remaining values) |

The enrollment body ID must equal the bearer's canonical lowercase UUID v4. `appVersion` is 1–64 characters from `[A-Za-z0-9+._-]`. Every requested schema ID must be accepted by policy. Secrets encode at least 32 random bytes as canonical unpadded base64url, at most 128 characters. Request bodies are capped at 1 MiB, including bodies without Content-Length.

| Applies to | Status | Error |
| --- | --- | --- |
| All five identity endpoints, database not wired | `503` | `db_unavailable` |
| All identity endpoints, malformed/missing bearer, ID mismatch, unknown ID or wrong secret | `401` | `unauthorized` |
| Authenticated endpoints, missing/invalid signature or timestamp outside ±300 seconds | `401` | `unauthorized` |
| Enrollment or authenticated endpoints, matching revoked credential (enrollment rejects any reuse of a revoked ID) | `401` | `revoked` |
| Enrollment, existing ID with another key | `409` | `install_exists` |
| Enrollment, unsupported schema | `422` | `unsupported_schema` |
| Enrollment/rotation, invalid JSON, unknown JSON key, invalid app version or new secret | `400` | `bad_request` |
| All identity endpoints, oversized body | `413` | `payload_too_large` |
| All identity endpoints, exhausted rate bucket | `429` | `rate_limited`, with `Retry-After` seconds |
| Unexpected failures | `500` | `internal_error` |

Rate limits use Ktor's [rate-limit plugin](https://ktor.io/docs/server-rate-limit.html): enrollment is globally shared at 120/hour per server process; authenticated routes share 60/minute per install; nonce issuance additionally permits 30/hour per install. Buckets use verified install IDs, never client IPs. The per-IP edge limiter is deferred to a custom-Caddy slice. Buckets are process-local and reset on restart.

Withdrawal deletes `trusted_envelopes`, `health_daily`, `token_sightings`, `cluster_sightings`, `ingest_ledger`, `nonces`, and `installs` in one transaction. Shared aggregate/catalog tables are retained. Deletion is synchronous; the deadline preserves a future asynchronous contract. Nonces are single-use with a 300-second validity window. HMAC signatures remain replayable within their timestamp window; S4 batch admission will use the ledger for replay protection.

Budget helpers expose a 300 accepted-skeleton default, 10 MiB, and 40 distinct batches per UTC day, with retries at the next UTC midnight. The ledger accumulates bytes, accepted/duplicate counts and rejection counts, deduplicating stored batch IDs. Ingest admission and atomic budget enforcement are S4, not identity endpoint behavior. The purge job starts one minute after startup and runs every six hours, deleting nonces older than one hour and ledger days strictly older than today minus seven days. Full table retention and operator revocation HTTP access arrive in S6; revocation storage is implemented now.
