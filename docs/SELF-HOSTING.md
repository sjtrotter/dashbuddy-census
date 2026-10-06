# Self-hosting

The server supports signed ingestion and authenticated operator review. Notifications default off (`CENSUS_NOTIFICATIONS_ENABLED=false`); the positive minimum filter revision defaults to 1 (`CENSUS_MIN_FILTER_REV`). Lifecycle replay and catch-up must complete before HTTP starts. Follow [activation and recovery](OPERATOR.md#notification-activation-and-rollback).

## Public deployment

Use a Linux host with Docker and Compose >= 2.24.4, public DNS, and inbound TCP 80/443. Follow the README's four commands. In `.env`, choose matching `POSTGRES_PASSWORD` and `DATABASE_PASSWORD`, fill `PUBLIC_HOST` and `ACME_EMAIL`, and set `OPERATOR_TOKEN_SHA256` to a 64-character hex digest. There are no default application secrets. The example `change-me` values are placeholders.

To hash a token without placing its literal value in shell history, in Bash:

```bash
read -rsp 'Operator token: ' census_operator_token
printf '%s' "$census_operator_token" | sha256sum
unset census_operator_token
```

Store the token in a password manager, and copy only the digest into `.env`. Remove the empty `SERVER_VERSION=` line: `env_file` would otherwise override the image's version and make policy report `dev`. Set `IMAGE_DIGEST` separately if desired. Database credentials configure a new volume; changing `.env` alone does not rotate an existing PostgreSQL role's password.

The stack uses an `edge` network for Caddy and census, allowing ACME egress, and an internal `db` network for census and Postgres. Postgres has no egress, and Caddy cannot reach it. Only Caddy publishes ports: 80/443 for the client API, and the operator listener on `OPS_BIND_IP:8443` (default `127.0.0.1`, so `/ops` is reachable only from the host — a loopback tunnel such as `ssh -L` or SSM port forwarding — or over a VPN address you set; the public site answers 404 for `/ops`, #1181). That listener uses Caddy's internal CA; trust its root certificate on the operator's device. Postgres and census have no published host ports. Caddy persists certificates, Postgres persists data, and census uses a read-only root with an ephemeral `/tmp`. Each service carries a memory limit sized for a 2 GiB host (census 704 MiB with the JVM bounded by `JAVA_TOOL_OPTIONS` to a 320 MiB heap, 96 MiB metaspace, 32 MiB direct memory counted for Netty too, a 48 MiB code cache, 512 KiB thread stacks and SerialGC, plus a 32 MiB `/tmp` tmpfs; Postgres 320 MiB with `shared_buffers=128MB`, `max_connections=20`, `work_mem=2MB`, `maintenance_work_mem=32MB`, `autovacuum_work_mem=16MB`; Caddy 96 MiB). A limit bounds ONE runaway service to its own cgroup; the limits sum to 1120 MiB so 715 MiB stays for the host, and the residual host-OOM risk is host, agent or transient use (a backup's aws-cli, a deploy's compose CLI) exceeding those 715 MiB — the 90 % memory alarm is the backstop (#1179). The per-service figures in the compose comments are estimates; the limits are the bounds. A HotSpot-raised heap/metaspace `OutOfMemoryError` exits census so Compose restarts it; a direct-buffer exhaustion degrades to failed requests. Raise the limits together with the host size; the JVM bounds must stay below the census limit. Configure host firewall and storage access accordingly. Access and runtime logs are discarded (ADR-0011). The edge image is upstream Caddy plus `caddy-ratelimit` (`deploy/caddy/Dockerfile`, published as `ghcr.io/sjtrotter/dashbuddy-census-caddy`, pinned in Compose by `CADDY_IMAGE_REF`): the public site admits at most 10 per minute and 20 per hour of `/v1/enroll`, and 200 `/v1` requests per minute, per client address, held ONLY in the plugin's memory — no access log, no retention, lost on restart (#1178). The operator listener is not per-IP limited: it is reachable only over the VPN from known peers and the application's own 60/minute operator bucket applies.

## Verify and pin a release

Given a published release and its manifest digest, substitute the tag and digest below:

```sh
cosign verify \
    --certificate-identity 'https://github.com/sjtrotter/dashbuddy-census/.github/workflows/image.yml@refs/tags/vX.Y.Z' \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    ghcr.io/sjtrotter/dashbuddy-census@sha256:REPLACE_WITH_DIGEST
```

Replace Compose's census image with that immutable reference. The workflow tests first, pushes only a candidate tag, and scans both architectures for CRITICAL/HIGH vulnerabilities. It generates the SBOM before promoting the candidate to the version tag and `latest`, then signs the immutable digest. Verify the signature and review the final workflow result and SBOM before deploying.

## Build your image

Bootstrap the wrapper JAR and pinned sibling app checkout as in the README. The two named contexts supply the contract sources (`census-contract`) and the app's version catalog (`dashbuddy-gradle`), which the contract reads from its sibling `gradle` directory. Replace `<DashBuddy>` with the app checkout path:

```sh
docker buildx build --build-context census-contract=<DashBuddy>/census-contract --build-context dashbuddy-gradle=<DashBuddy>/gradle -t dashbuddy-census:local .
```

Use `--load` if your Buildx driver does not automatically load the image into the local Docker engine, and set Compose's census `image` to `dashbuddy-census:local` to run it. CI supplies both contexts from a checkout at `DASHBUDDY_CONTRACT_SHA`. Actions are pinned to commit SHAs; base-image digests remain a release-hardening TODO.

## Local HTTP

Use a separate local Compose project so a previously running public Caddy is not left behind:

```sh
cd deploy/compose
docker compose -p census-local -f docker-compose.yml -f docker-compose.local.yml up -d
curl -fsS http://localhost:8080/healthz
```

The override puts Caddy behind an inactive `public-edge` profile, clears its ports, and publishes census only on `127.0.0.1:8080`; its fixed container port 8080 is also assumed by the healthcheck and Caddy upstream. Do not enable that profile locally. Use the same `-p` and `-f` flags for subsequent local management commands; the backup/restore scripts target the normal public stack. See [the runbook](OPERATOR.md) for backups, rotations, and restore drills.
