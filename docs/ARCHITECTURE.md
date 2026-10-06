# Architecture — #1157 S1

```text
                  AWS us-east-2: default VPC, Elastic IP
                  +-- Ubuntu arm64 t4g.small / Docker Compose --------+
Phone --HTTPS---->| Caddy --HTTP--> census --JDBC--> Postgres           |
                  | TLS, no access  policy, health,  encrypted gp3     |
                  | log             Flyway schema   daily pg_dump    |
                  +--------------------------------------------------+
                        ^                 |                 |
GitHub OIDC / operator --SSM (no SSH)     host metrics      S3 backups
                        ^                 |                 14-day lifecycle
                   SSM parameters     CloudWatch --> SNS email

AWS wrapper: account budgets + automatic EC2 stop; daily cost anomalies;
             multi-region CloudTrail management events --> S3 (14 days)
```

The phone/ingest arrow describes the target system; S1 exposes only health, readiness, and policy. Census starts by validating environment configuration, migrating the database, and creating a five-connection Hikari pool registered with Exposed. Startup aborts on migration failure. Readiness runs a bounded `SELECT 1`; liveness never checks the database. There is no ingest, scheduler, token promotion, or dashboard implementation yet.

## Contract seam

`../DashBuddy/census-contract` is an Apache-2.0 Gradle included build with coordinates `cloud.trotter.census:contract:0.0.0-local` and package `cloud.trotter.census.contract`. `censusContractPath` overrides its location. A reference to `CensusHash` proves dependency substitution at compile time. DTOs, fingerprinting, hashing, and grammars stay in that build; no crypto dependency or duplicate wire implementation is introduced here. The hash-domain constant is a TODO because the sibling checkout was unavailable during bootstrap.

## Storage and deployment boundary

The eleven application tables preserve hashes, dates, counts, and future operator state. Flyway adds its own schema-history table. The schema does not enforce the per-version five-sample cap, k-anonymity, quarantine, withdrawal, or scheduled retention: future ingest and maintenance transactions must do that. A hash with zero sightings must not acquire an existence claim from an empty aggregate.

Configuration is injected, stdout carries logs, and PostgreSQL holds durable state. The runtime is non-root, drops capabilities in Compose, and writes only under `/tmp`. Flyway coordination handles concurrent migration attempts, but operators must still review backward compatibility for rolling deployments.

## AWS wrapper — S2

[deploy/aws](../deploy/aws/README.md) wraps the same Compose stack in one Graviton EC2 instance, with IMDSv2, encrypted storage, restricted egress, and SSM-only administrative access. Parameter Store supplies startup secrets. Daily database dumps go to a private versioned S3 bucket; current and noncurrent versions have 14-day expiration rules. CloudWatch collects host memory/root-disk metrics and alarms alongside EC2 status/CPU metrics; no app logs are shipped. CloudTrail records management events separately from application traffic.

GitHub Actions assumes a ref-restricted OIDC role to deploy through SSM. Account-wide budgets notify and automatically stop the instance at the configured $50 threshold, subject to billing delay; this is not a guaranteed spending cap. Native S3 lockfiles protect Terraform state. The single root volume is a single point of failure, so instance replacement requires a backup/restore plan. No ECS, RDS, load balancer, or Kubernetes layer is introduced. Future ingress and logging changes must preserve the no-IP-retention promise.

## Notification storage and operator boundary — #1189 slice 2

Both workflows pin the included DashBuddy contract to reviewed commit `b0fd522a354ca0d6d38d74324cdea0f227859648`. The server uses its DTOs, dispatcher, fingerprint and rejection vocabulary directly. Conformance reads the pinned JSONL corpus, notification vectors and manifest (counts and SHA-256), rather than fixed row counts. The synthetic metadata identity vector remains valid at the contract layer but is rejected by the unchanged server release-tag policy as `bad_version`.

V4 adds checked, non-null `clusters.kind`, backfilling existing rows to `screen`; V1–V3 are unchanged. Ingest persists the submitted contract kind without updating an existing cluster's kind and refuses conflicting kinds transactionally. Canonical samples, cluster sightings, token sightings and shared quota accounting commit in one transaction. Samples are capped at five per `(fingerprint, platform_app_version)`, with at most one per receipt day. Tokens retain their existing cross-surface hash domain and `(token_hash, install_id)` key: equal chrome tokens count an install once across screen and notification surfaces.

Operator samples are bounded allowlisted projections. Notification channels and notes use the live k=10 community-install gate in the existing 28-day ops counting window, or the existing trusted-sighting exception; falling below the gate hides them again. Slot hashes never leave sample projections, including above the gate. Historical samples with expired hashes can render kind badges without recomputing or changing cluster identity. Notifications never select a trusted screen capture or wireframe and cannot generate/store/download rule drafts. Classification uses the existing `screen_class` column and vocabulary. Health schemas, fleet denominators and alarm thresholds are unchanged.

**Activation prerequisite remains outstanding; production policy stays screen-only.** The existing PurgeJob lacks token/cluster-sighting/sample retention sweeps; vocabulary eligibility lacks quarantine/expiry restrictions; persisted token/vocabulary keys lack hash-domain/filter-revision provenance; policy and validation lack a minimum accepted filter revision. Repair these in a separate prerequisite before notification activation. This slice does not claim those lifecycle repairs. ADR durations remain seven days quarantine, 30 days token sightings/active window, 90 days cluster sightings, 30 days trusted envelopes, 180 days health, seven days ledger, and 14 days backups; k remains 10. Deploy server support before enabling any app publisher, and pin/replay again when slice 3 changes the golden.
