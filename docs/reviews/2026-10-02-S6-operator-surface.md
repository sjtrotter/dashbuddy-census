# Review record — S6a operator surface (2026-10-02)

Scope: bearer-protected `/ops/*` JSON and read-only kotlinx.html dashboard, mutation TOTP,
k-gate projection (#1175), operator reads/mutations, and delivered-alarm snapshots.
Schema and CLIENT.md unchanged. SNS delivery and TOTP provisioning remain S6b.

Builder validation: source review and `git diff --check` only. No Gradle, network, build,
or test execution in the builder sandbox; the operator builds and runs tests after hand-off.
The server already declared `implementation(libs.kotlinx.html.jvm)` before this slice.

| Round | Outcome |
| --- | --- |
| Astra: **DO NOT MERGE** (1 P1, 1 P2) — P1 Ktor's route-scoped `RateLimit` runs AFTER the auth plugin's `onCall` and skips handled calls, so 401s (wrong bearer, wrong/replayed TOTP) never spent a token: unlimited second-factor guesses with a stolen bearer, and a 429 could still CONSUME a valid code → the bucket is now an in-plugin `OpsBucket` decided FIRST (60/min, every request), the Ktor `ops` limiter is gone, and a refused request never reaches the verifier; test: 100 wrong codes → 60×401 + 40×429, exhausted bucket + valid code → 429 then the same code is accepted after refill. P2 operator `notes` (free text that can quote a label/hash/UUID) were returned verbatim regardless of k → serialised only when `unblinded`, else `notesWithheld: true`; test on a below-k cluster. Round 0 (session): kotlinx.html `<style>` `unsafe { raw }`; gitleaks allow-list for the RFC 6238 Appendix B key (and the sweep's exit status now gates every commit). | Session to fill: operator build/test results and review findings. |
