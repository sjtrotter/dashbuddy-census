# Security

Report vulnerabilities privately through the repository's GitHub **Security → Report a vulnerability** flow when enabled. If unavailable, ask a maintainer for a private reporting channel without posting exploit details or sensitive data publicly. Establish and test the reporting channel before production enrollment.

Scope includes authentication/enrollment, withdrawal, privacy leaks, ingest validation, k-anonymity and quarantine bypasses, database access, dependency/image integrity, and operator access. The API and ingestion controls beyond the three public GET routes are planned, so this bootstrap is not ready for collecting app data.

The poisoning model assumes an attacker can enroll or control multiple installs and submit misleading skeletons. The only prize is promoting a token into the vocabulary, which needs at least k distinct installs past quarantine AND the human drafting gate. Per-install budgets, admission controls, replay protection, review, and eventual revocation must support that boundary; hashes alone neither prevent poisoning nor prove anonymity. These protections are design targets, not implemented ingest defenses in S1.

Store only the SHA-256 digest of the operator token in configuration. Use a high-entropy token and constant-time verification when authentication lands; the digest is not a substitute for token entropy. Never log bodies, bearer credentials, full install IDs, or client IPs. The schema permits reviewed plaintext vocabulary after the gate; trusted-envelope validation must still prevent below-threshold plaintext. Nonce issuance and revocation timestamps are the only sub-day data exceptions.

The release workflow signs image digests keylessly, produces an SBOM, and fails on CRITICAL vulnerabilities. Production operators must verify signatures, inspect the complete workflow result, and pin immutable digests. Review cloud logging, PostgreSQL/host logs, backup access, and 14-day deletion policies as part of deployment. AGPL-3.0-only applies to this server; the independently maintained contract remains Apache-2.0.
