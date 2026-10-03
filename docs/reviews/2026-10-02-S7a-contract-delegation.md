# Review record — S7a contract auth delegation (2026-10-02)

| What moved | Oracle |
| --- | --- |
| Install ID grammar, secret validation/hash, bearer parsing, canonical bytes, signing, public verification, and timestamp validation delegate to the contract pinned at `3275ad6b6dad827155d1b4cf8b33fbcef8bc64d0`. The contract's internal comparator seam is inaccessible across modules, so the server retains header-shape checks plus the injected comparator over contract `sign`; its default remains `MessageDigest.isEqual` for the unchanged source guard. The internal contract digest helper is also inaccessible, so `sha256Hex` extracts the canonical body-digest field. Nonce hex rendering delegates to JDK `HexFormat`. | Existing server tests remain the parity oracle; only `RequestSignerTest` gains one worked-vector assertion comparing server and contract `sign`. Static review and `git diff --check`; no Gradle, network, or test execution in the sandbox. |

Session note: the builder derived `sha256Hex` by slicing the contract's canonical string; replaced with a direct `MessageDigest` helper (not wire-auth; nonces/state only). No Astra round for this refactor — the 107 unchanged server tests are the parity oracle (incl. the new worked-vector equality against the contract signer); CI runs the same suite against the pinned contract SHA.
