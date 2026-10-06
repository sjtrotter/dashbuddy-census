# Architecture

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

Census validates environment configuration, migrates through V5, replays withdrawals, applies the monotonic filter floor, and drains startup lifecycle work before accepting HTTP. The five-connection Hikari pool is registered with Exposed. Migration, recovery or lifecycle failure aborts startup. Readiness runs a bounded `SELECT 1`; liveness does not check the database. Signed, budgeted ingestion and authenticated operator review share the same effective policy.

## Contract seam

`../DashBuddy/census-contract` is an Apache-2.0 Gradle included build with coordinates `cloud.trotter.census:contract:0.0.0-local` and package `cloud.trotter.census.contract`. `censusContractPath` overrides its location. A reference to `CensusHash` proves dependency substitution at compile time. DTOs, fingerprinting, hashing, and grammars stay in that build; no crypto dependency or duplicate wire implementation is introduced here. The included contract owns hash-domain, DTO and fingerprint constants; both workflows retain pin `3943c39d44a80eaecd93b8caa36147c6056ee168`.

## Storage and deployment boundary

V5 adds three attributable replacement tables and one policy-only floor table, retaining empty legacy tables. Tokens and vocabulary partition by `(hash_domain, filter_rev, token_hash)`; token evidence additionally keys by install. Cluster sightings carry exact domain/revision and observation day. Sample caps remain five per fingerprint/app-version and one per receipt day across revisions. Validated observation days, clamped to receipt day for the one-day future tolerance, govern evidence age; retries and backfills do not refresh it. V1–V4 and their checksums are unchanged.

Configuration is injected, stdout carries logs, and PostgreSQL holds durable state. The runtime is non-root, drops capabilities in Compose, and writes only under `/tmp`. Flyway coordination handles concurrent migration attempts, but operators must still review backward compatibility for rolling deployments.

## AWS wrapper — S2

[deploy/aws](../deploy/aws/README.md) wraps the same Compose stack in one Graviton EC2 instance, with IMDSv2, encrypted storage, restricted egress, and SSM-only administrative access. Parameter Store supplies startup secrets. Daily database dumps go to a private versioned S3 bucket; current and noncurrent versions have 14-day expiration rules. CloudWatch collects host memory/root-disk metrics and alarms alongside EC2 status/CPU metrics; no app logs are shipped. CloudTrail records management events separately from application traffic.

GitHub Actions assumes a ref-restricted OIDC role to deploy through SSM. Account-wide budgets notify and automatically stop the instance at the configured $50 threshold, subject to billing delay; this is not a guaranteed spending cap. Native S3 lockfiles protect Terraform state. The single root volume is a single point of failure, so instance replacement requires a backup/restore plan. No ECS, RDS, load balancer, or Kubernetes layer is introduced. Future ingress and logging changes must preserve the no-IP-retention promise.

## Notification storage and operator boundary — #1189 slice 2

Both workflows pin the included DashBuddy contract to reviewed commit `3943c39d44a80eaecd93b8caa36147c6056ee168`. The server uses its DTOs, dispatcher, fingerprint and rejection vocabulary directly. Conformance reads the pinned JSONL corpus, notification vectors and manifest (counts and SHA-256), rather than fixed row counts. The synthetic metadata identity vector remains valid at the contract layer but is rejected by the unchanged server release-tag policy as `bad_version`.

V4 adds checked, non-null `clusters.kind`, backfilling existing rows to `screen`; V1–V3 are unchanged. Ingest persists the submitted contract kind without updating an existing cluster's kind and refuses conflicting kinds transactionally. Canonical samples, cluster sightings, token sightings and shared quota accounting commit in one transaction. Samples are capped at five per `(fingerprint, platform_app_version)`, with at most one per receipt day. V5 tokens use `(hash_domain, token_hash, filter_rev, install_id)`: equal chrome tokens count an install once across screen and notification surfaces.

Operator samples are bounded allowlisted projections. Notification channels and notes use the live k=10 community-install gate in the existing 28-day ops counting window, or the existing trusted-sighting exception; falling below the gate hides them again. Slot hashes never leave sample projections, including above the gate. Historical samples with expired hashes can render kind badges without recomputing or changing cluster identity. Notifications never select a trusted screen capture or wireframe and cannot generate/store/download rule drafts. Classification uses the existing `screen_class` column and vocabulary. Health schemas, fleet denominators and alarm thresholds are unchanged.

## Shared lifecycle and configuration activation — #1189 slice 4

`CENSUS_NOTIFICATIONS_ENABLED` is strictly `true|false`, default `false`; `CENSUS_MIN_FILTER_REV` is a positive integer, default 1. The effective floor is the maximum of configuration, database policy and imported recovery journal. `/v1/policy`, enrolment and admission use one policy. Positive old revisions are rejected as server-owned `filter_rev_too_old`; malformed revisions remain `bad_item`. Claimed revision is not filter attestation or server reclassification.

Live vocabulary eligibility requires k=10 distinct unrevoked, non-trusted installs, seven elapsed days since enrolment, accepted provenance and unexpired per-install token evidence. Cohorts never pool domains or revisions. V3 legacy day-only enrolment uncertainty remains; token first sightings never infer earlier enrolment. Every queue/count/display/resolve re-evaluates eligibility; operator resolution locks contributor parents and queries again. Hash-free dashboard projections, human review, trusted screen drafting at k=1 and notification capture/draft restrictions remain. Server deletion cannot retract a shipped bundle.

Token evidence expires at last observation +30 days; cluster evidence and unresolved samples at +90 days. Resolution stamps once and caps sample lifetime at +30, including after reopening. Sample sweeps independently find orphan hashes, replacing every expired slot with `{"kind":"expired"}` without changing fingerprints. Reads also enforce deadlines; notes expire with samples. Inactive installs use parent-first erasure at 365 days. Envelopes expire at +30 with coordinated draft deletion; health=180, ledger=7, backups=14 and withdrawal tombstones=60 days. All deadlines are half-open UTC (`deadline <= today`).

Startup catch-up drains capped work or fails closed; runtime passes run at UTC 00/06/12/18 with prompt continuation after caps and an independent seven-hour watchdog. Sweeps use bounded transactions, cancellation propagation and independent failure reports. Ingest and sample maintenance share cluster locks and recheck after locking. `/ops/lifecycle` and its dashboard summary contain activation, floors, aggregate counts/ages and bounded failure indicators only. Lifecycle failure/backlog/stale alarms use the existing sink and host publisher.

Cutover requires stopping the old server. Unknown-provenance legacy sightings and vocabulary are discarded, valid samples get embedded provenance and lose all legacy hashes, malformed samples are removed, and historical resolved samples expire immediately. Derived notes/drafts and inseparable captures are cleared; classification survives while the catalogue row is needed. Old tables stay empty and unused; later unexpected legacy writes reject startup. This deliberately resets historical cohorts and requires fresh evidence to rebuild k.

Floor increases durably commit before removal and replay after interruption or older-backup restore. Restore requires current deployment policy plus the latest off-host floor and withdrawal journals. Floor journals are policy-only and retained while any older dump is restorable. Notifications remain disabled until integration tests, restore drill, zero overdue work and alarm delivery are verified; see [activation and rollback](OPERATOR.md#notification-activation-and-rollback).
