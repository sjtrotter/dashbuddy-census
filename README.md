# dashbuddy-census

DashBuddy Census will help the DashBuddy Android app understand unfamiliar screens without collecting their words. Enrolled installs will send UI skeletons containing hashes. The server will group structurally similar screens and count distinct installs for each token hash, so one noisy install cannot make a token appear common.

What is live (v0.11.0):

- Enrol, rotate, revoke, and withdraw, with signed requests.
- Skeleton ingest (`uinode.skeleton.v1`), trusted-envelope ingest (`uinode.v1`, trusted installs only, paired to existing clusters by fingerprint), and daily recognition health (`/v1/health`).
- Structural-fingerprint clustering, k-gated label unblinding, and the vocabulary queue.
- Health rollup and alarms delivered by email.
- The operator dashboard, served ONLY on the WireGuard listener with bearer or TOTP session login: platform → version cluster summary, per-group review pages, and cluster detail with skeleton samples and the trusted-capture wireframe.
- The purge job for retention enforcement.
- AWS deployment with Terraform, SSM, an image pinned by digest, and a Caddy edge with per-IP rate limits.

Versions:

- v0.6.0: S7 server slice live.
- v0.7.0: VPN-only `/ops` and TOTP login.
- v0.8.0: human-readable dashboard and cluster detail.
- v0.9.0: platform → version cluster summary and review pages.
- v0.10.0: envelope → cluster pairing and the trusted-capture wireframe; the validator accepts real phone captures.
- v0.11.0: classify & draft a rule from the trusted-capture wireframe.

This repository is AGPL-3.0-only. The wire contract belongs to the app's separate Apache-2.0 `census-contract` included build; it is not copied into this repository.

## OPERATOR TRUST STATEMENT — draft

Our intended operating promise is to store hashes and counts, never plaintext below **k = 10 distinct community installs** after a **7-day quarantine**, and to require human review before drafting a rule. **One stated exception:** a *trusted* install — the operator's own device, enrolled explicitly and excluded from the k count — is unblinded at k = 1 by definition, because the operator is reading their own phone's redacted captures (see the display gate in [OPERATOR.md](docs/OPERATOR.md)). We never persist or log client IP addresses — no exception, not even during an abuse incident; the Caddy edge rate limiter retains the peer address only in process memory for one window. We do not collect hardware/OS device identifiers. "Only credential digests are stored" is true of CLIENT bearers (`installs.key_hash` is a SHA-256); the host does hold two recoverable operator secrets — the TOTP seed and the database password — as an SSM SecureString rendered into a root-only `.env`, encrypted at rest, not hashed. A random, revocable install ID supports distinct-install counting. Observations use dates rather than timestamps finer than a day; nonce issuance and revocation are explicit security exceptions. Caddy discards access and runtime logs and strips forwarded client-IP headers, and the application excludes IPs, credentials, and bodies from request logs.

| Data | Retention |
| --- | --- |
| Token sightings | 30 days |
| Cluster sightings | 90 days |
| Trusted envelopes | 30 days |
| Per-install daily health | 180 days |
| Ingest ledger | 7 days |
| Backups | 14 days |

The right to withdraw will be exposed through `DELETE /v1/installs/me` (planned). Backups age out within 14 days; a restore must reapply withdrawals made since the backup. Aggregate/catalog retention and sample pruning still need their implementation policy. S1 creates the schema and advertises these targets but does not yet enforce ingest or deletion rules.

The exact Compose file we intend to run is [this repository's file](deploy/compose/docker-compose.yml), with a production image digest pinned. The release workflow signs the image using keyless Cosign and publishes an SBOM; operators must verify the actual release signature before deploying. S1 does not claim an image has already been published or verified.

## Self-host in four commands

From this checkout, with Docker Compose >= 2.24.4 installed and public DNS pointing at the host:

```sh
cd deploy/compose
cp .env.example .env
${EDITOR:-vi} .env
docker compose up -d
```

During editing, set `PUBLIC_HOST`, a real `ACME_EMAIL`, matching strong database passwords, and the operator token's SHA-256 hex digest. Remove the empty `SERVER_VERSION` line to retain the image's baked-in version. Replace the image tag expression in Compose with the verified `@sha256:<digest>` for production. Only Caddy publishes ports. See [self-hosting](docs/SELF-HOSTING.md) for local HTTP, building an image, and signature verification.

## Build

Check out the app at the agreed pinned revision as `../DashBuddy`; its `census-contract/` must provide `cloud.trotter.census:contract:0.0.0-local`. The Gradle wrapper (script + jar) is committed, so `./gradlew` works out of the box:

```sh
# (the Gradle wrapper jar is committed — `./gradlew` works out of the box)
./gradlew build
./gradlew :server:installDist
```

An alternate checkout uses `-PcensusContractPath=/absolute/path/to/census-contract`. Foojay provisions JDK 21. The distribution is `server/build/install/server/`; there is no fat JAR. Migration tests require Docker in CI and visibly skip locally when it is unavailable; route and configuration tests need no database. Both CI workflows pin `DASHBUDDY_CONTRACT_SHA` to `b0fd522a354ca0d6d38d74324cdea0f227859648` and use the contract checkout at that revision.

Versions were pinned offline on 2026-10-01. Gradle, Kotlin, serialization, Ktor, Exposed, and the other catalog versions need validation together on the first build. Bump exact pins if necessary; do not replace them with ranges. The absent sibling contract also means `hashDomain` uses the explicit `census.v1` TODO fallback until its API is inspected.

## Scale path

Start with Compose, move the same image to ECS Fargate with RDS, then add EKS/Helm if needed. Configuration comes from the environment, state lives in PostgreSQL, logs go to stdout, and disposable processes write only temporary files under `/tmp`. These properties make orchestration and managed backing services additive; migrations still need coordinated rollout, and future schedulers need a single owner or database locking.

Read the [architecture](docs/ARCHITECTURE.md), [API status](docs/API.md), [operator runbook](docs/OPERATOR.md), [security model](docs/SECURITY.md), and [contributing guide](CONTRIBUTING.md). See [LICENSE](LICENSE) for the complete AGPL-3.0 text; this project's grant is AGPL-3.0-only.
