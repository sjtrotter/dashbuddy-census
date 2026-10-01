# API status — #1157 S1

Only the three public GET endpoints are implemented. The original plan's §3.2 endpoint table was not supplied with this bootstrap; the remaining rows below are provisional placeholders, not an authoritative wire contract. Reconcile their paths and methods with that plan before implementation.

| Method | Path | Status | Purpose |
| --- | --- | --- | --- |
| GET | `/healthz` | Implemented | Process liveness, no database |
| GET | `/readyz` | Implemented | PostgreSQL `SELECT 1` readiness |
| GET | `/v1/policy` | Implemented | Public policy and build identity |
| POST | `/v1/nonces` | Planned, provisional path | Enrollment challenge |
| POST | `/v1/installs` | Planned, provisional path | Enroll an install |
| DELETE | `/v1/installs/me` | Planned | Withdraw the authenticated install |
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

The required `tokenSightingsDays` name contains “token”; the privacy test exempts only that field name while rejecting credential-related text and configured secrets. Unknown JSON keys are configured to reject on typed decoding; S1 has no request-body endpoint yet. Errors use `{"error":"<code>"}`, without stack traces. Optional `X-Install-Id-Prefix` logging accepts only 1–8 hex characters; full install IDs, arbitrary paths, and query strings are not logged. Authentication and prefix conventions must be reconciled with the app contract in the ingest slice.
