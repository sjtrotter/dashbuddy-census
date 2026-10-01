# Architecture — #1157 S1

```text
Phone --HTTPS--> Caddy --HTTP--> census --JDBC--> Postgres
                   |                |
             TLS, no access    policy, health,
                 log           Flyway schema
```

The phone/ingest arrow describes the target system; S1 exposes only health, readiness, and policy. Census starts by validating environment configuration, migrating the database, and creating a five-connection Hikari pool registered with Exposed. Startup aborts on migration failure. Readiness runs a bounded `SELECT 1`; liveness never checks the database. There is no ingest, scheduler, token promotion, or dashboard implementation yet.

## Contract seam

`../DashBuddy/census-contract` is an Apache-2.0 Gradle included build with coordinates `cloud.trotter.census:contract:0.0.0-local` and package `cloud.trotter.census.contract`. `censusContractPath` overrides its location. A reference to `CensusHash` proves dependency substitution at compile time. DTOs, fingerprinting, hashing, and grammars stay in that build; no crypto dependency or duplicate wire implementation is introduced here. The hash-domain constant is a TODO because the sibling checkout was unavailable during bootstrap.

## Storage and deployment boundary

The eleven application tables preserve hashes, dates, counts, and future operator state. Flyway adds its own schema-history table. The schema does not enforce the per-version five-sample cap, k-anonymity, quarantine, withdrawal, or scheduled retention: future ingest and maintenance transactions must do that. A hash with zero sightings must not acquire an existence claim from an empty aggregate.

Configuration is injected, stdout carries logs, and PostgreSQL holds durable state. The runtime is non-root, drops capabilities in Compose, and writes only under `/tmp`. Flyway coordination handles concurrent migration attempts, but operators must still review backward compatibility for rolling deployments.

## AWS wrapper — planned

ECS Fargate task definitions, RDS, secret injection, TLS/load balancing, backup policy, and infrastructure-as-code wrap the same application image. EKS/Helm can follow without changing the contract or adding local persistence. Any managed proxy/logging replacement must preserve the no-IP-retention promise. Task count and the five-connection pool must fit the database connection budget; future maintenance jobs need coordination. No AWS resources or Helm charts ship in S1.
