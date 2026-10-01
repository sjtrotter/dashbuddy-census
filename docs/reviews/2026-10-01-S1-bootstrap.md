# Review record — S1 bootstrap (2026-10-01)

The server has no pull request for its first commit, so the adversarial-review record the project's
doctrine attaches to every merge lives here. Builder: codex `gpt-6-astra` from a fable-written spec.
Reviewers: codex Astra (xhigh, read-only, independent) + the session's own read; local verification
`./gradlew build` under Docker (14 tests incl. Testcontainers Postgres 16), image build, compose smoke.

| Round | Outcome |
|---|---|
| 1 — Astra: **DO NOT MERGE**, 8 confirmed | (1) Caddy's RUNTIME log still retained client IPs on errors → global `log { output discard }`; (2) a bad `DATABASE_URL` leaked its embedded password/user/port via Flyway, pgJDBC and the uncaught-exception path → sanitized startup boundary (class name only), JUL→SLF4J, driver/Flyway loggers quietened, bounded connection timeouts, `StartupLeakTest` runs `main` in a subprocess with sentinels and asserts none appear; (3) `aquasecurity/trivy-action@0.28.0` did not exist → every action pinned to a commit SHA; (4) the HTTP method reached the request log as attacker text → allowlist → `OTHER`, log-capture tests; (5) the release workflow tagged and signed BEFORE scanning and never ran tests → `test` job gates `image`, candidate digest scanned (CRITICAL/HIGH fail) and SBOM'd before promotion + signing; (6) mutable action tags → SHA pins; (7) the documented image build omitted the second build context → fixed; (8) local compose bound 0.0.0.0:8080 → 127.0.0.1. Also applied: edge/db network split (`db` internal, Postgres on it alone), fixed port 8080, `MigrationTest` requires Docker when `CI=true`, readiness-with-database test, column-level schema assertions, identifier-column guard, FKs on `nonces.install_id` / `trusted_envelopes.fingerprint`, wider 12-factor guard. Rejected: a `trips → journeys` rename the builder made to dodge a substring guard — the guard now matches tokens. |

Verified after the round: 14/14 tests; image 334 MB; compose: `/readyz` 200, `BEARERSECRET /healthz` → 405 logged as `method=OTHER`, `compose_db` internal=true with Postgres attached to it only, census published on `127.0.0.1:8080` only. Secret sweeps on the staged tree: file patterns, token/key regexes and gitleaks — zero findings.
