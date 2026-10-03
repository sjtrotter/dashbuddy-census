# Review record — S8 operator VPN + session login (2026-10-03, DashBuddy #1181)

Scope: take `/ops` off the public internet (WireGuard between the operator's devices and the host; the
operator surface served only on a Caddy listener bound to the tunnel address with the internal CA; the
public site answers 404 for `/ops`), then a browser login on the operator token + TOTP that sets a
hardened single session cookie; reads accept cookie or bearer, mutations still require a fresh
`X-Census-Totp`. Also this day: #1179 memory limits (PR #33) + the first restore drill (PR #34) and
the blind memory metric (PR #35) — recorded in those PRs.

Builder: the VPN half by the session (Terraform, cloud-init, Compose, Caddy, runbook); the login half
by codex `gpt-6-astra` from a fable-written spec (no gradle in the builder sandbox; the session built
and ran the suites: 127 server tests incl. Testcontainers).

| Round | Outcome |
| --- | --- |
| VPN 1 (PR #36, 957c3c4) | fable: APPROVE WITH FIXES (a transient Parameter Store failure tore the tunnel down via `\|\| true`; IP-literal clients send no SNI and behind Docker NAT certificate selection needs `default_sni`; nits). Astra: **DO NOT MERGE** — `path /ops /ops/*` runs on the CLEANED path while the proxy forwards the original URI and Ktor keeps dot-segments literally, so `--path-as-is /ops/../healthz` reached the upstream `/ops` catch-all and its admission bucket; the same two should-fixes; runbook: new-host restart step, per-device addresses. All applied. |
| VPN 2 (delta) | Astra: **DO NOT MERGE** — `path_regexp` ALSO cleans; the restart wording was wrong (systemd runs ExecStop before the failing pre-start, so the stack stays stopped); the `<(wg-quick strip)` producer escaped `pipefail` and an empty strip would have synced an empty config. Applied: `vars_regexp` on `{http.request.uri.path}` (raw decoded path); strip run and checked on its own; runbook sentence. |
| VPN 3 (delta) | Astra: **MERGE** — every form Ktor 3.6 routes into `/ops` is blocked (`/ops`, `/ops/`, `//ops`, `/%6fps`, `/ops/../x`; `/x/../ops` stays literal and never routes). Merged e093b89; rolled out 14:43 UTC; the no-SNI TLS path verified from the host against the internal CA (`ssl_verify_result 0`), public `/ops/` and the traversal form → 404. |
| Login 1 (PR #37, 86ef14b) | fable: APPROVE WITH FIXES — Ktor's checked `URLDecodeException` on `token=%ZZ` escaped the `IllegalArgumentException` catch to the generic 500 whose message embeds the whole form body (one log line from leaking the token) → StatusPages maps it to 400; no `Cache-Control: no-store` on the now browser-rendered reads → set after every successful authentication; `frame-ancestors 'none'`; `replay` made required; docs. Astra: MERGE WITH FIXES — a browser sends a space as `+`, which Ktor's parser keeps literal, so a token that authenticates as a bearer failed through the form → `+` translated before decoding, cap raised to 1024 (form + handler); `BadContentTypeFormatException` → 400; the framing and docs items shared with fable. Accepted without change: the token-first timing oracle. Added on the session's own account: an unauthenticated HTML `GET` under `/ops` redirects to the form. |

Confirmed sound by both login reviewers: the `/ops/login` exemption is byte-exact and narrower than the
route; no cookie-authenticated mutation without TOTP except logout; an explicit bearer never falls back
to the cookie; token verified before the shared replay memory; sessions server-minted (no fixation),
hash-only, constant-time, single, lost on restart; Ktor 3.6 renders `SameSite=Strict` and `Max-Age=0`;
no credential in any log line (log test).

Open: DashBuddy #1192 (withdrawal replay after a restore). Defense in depth not yet built: the app
itself does not know which listener a request arrived on; the public 404 is the edge's.
