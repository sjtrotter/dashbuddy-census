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
