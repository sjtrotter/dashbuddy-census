# Census installer and uploader protocol

Use HTTPS. Generate one canonical lowercase UUID v4 (`UUID.randomUUID().toString()`) per installation and a secret from at least 32 cryptographically random bytes. Encode those bytes as canonical base64url without `=` padding; the encoded secret must be at most 128 characters. Never derive credentials from device identifiers. Keep the secret in encrypted app-private storage wrapped by an Android Keystore key, exclude it from backups, and never put credentials, signatures, bodies, or full install IDs in logs or crash reports.

Enroll with `POST /v1/enroll`, `Content-Type: application/json`, and `Authorization: Bearer <installId>.<secret>`. For example, the JSON has `installId`, `appVersion` (`1.2.3+42`, at most 64 allowed ASCII characters), and `schemaIds` (`["uinode.skeleton.v1"]`). Enrollment does not require an HMAC. A successful response is the complete policy plus `installIdPrefix`; retain policy limits. Repeating the same ID and secret is idempotent. A conflicting secret returns `409 install_exists`; do not silently replace an existing installation's credentials.

Every request to an authenticated route includes the bearer plus:

| Header | Value |
| --- | --- |
| `X-Census-Timestamp` | Unix seconds as a decimal integer, with no `+`, leading zeroes, whitespace, or fractions |
| `X-Census-Signature` | `v1=` followed by 64 lowercase hex characters |

The shared `RequestSigner` object defines the byte-exact algorithm. Construct this string, with exactly three single LF bytes (`0a`), no CR bytes, and **no trailing newline**:

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + SHA256_HEX(RAW_BODY)
```

`METHOD` is the actual uppercase HTTP method. `PATH` is the URL path as transmitted (preserve percent escapes and any trailing slash), without scheme, host, query, or fragment. Query parameters are not authenticated and must not carry authenticated decisions. `TIMESTAMP` is exactly the header value. `RAW_BODY` is exactly the transmitted byte sequence, including JSON whitespace and property order; for an absent body use zero bytes. Hash the raw bytes with SHA-256 and render 64 lowercase hex characters. Encode the canonical string as UTF-8. HMAC-SHA256 uses the **base64url-decoded secret bytes** as its key. Prefix the lowercase hex result with `v1=`. The stored credential hash, separately, is SHA-256 of the UTF-8 **encoded secret string**.

The server accepts timestamps within ±300 whole seconds of its UTC clock, inclusive. Synchronize the device clock; a rejected timestamp and a bad signature both return `401 unauthorized`. An identical signed request can be replayed within that window. HMAC authenticates bytes, not uniqueness. S4 will enforce ingest replay protection using the per-install, per-UTC-day `batchId` ledger; callers must use a stable batch ID when retrying the same batch. S3 has the ledger primitives but no ingest endpoint or batch admission enforcement yet.

Worked vector (also asserted by `auth/RequestSignerTest`): the key bytes are `00 01 02 ... 1f`, encoded as:

```text
AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
```

For `POST /v1/nonce`, an empty body, and timestamp `1790899200`, the canonical string is the following four lines, with no newline after the final `5`:

```text
POST
/v1/nonce
1790899200
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

The resulting header is:

```text
X-Census-Signature: v1=bcf991bb04da013f724bd200abe7e8352c68dbd7155aacbcb6a1a1dff94cbefd
```

This public fixture is for testing only. It must not be used as a real credential.

`POST /v1/rotate` takes `{"newSecret":"<fresh encoded secret>"}` and must be signed with the **old** secret over the exact new-secret body. A `204` response means the old key no longer authenticates subsequent requests. Rotation and withdrawal revalidate the current credential inside their own database transaction; a stale secret receives `401 unauthorized`, including when another request rotated the key or withdrew and re-enrolled the same UUID after authentication. Persist the new secret securely before discarding the old one. If the response is lost, try a signed `GET /v1/me` using the pending new key to resolve whether rotation completed. A leaked bearer includes the signing key and must be treated as full credential compromise; see [SECURITY.md](SECURITY.md).

`POST /v1/nonce` with an empty signed body returns a 32-hex nonce and `expiresInSeconds: 300`. The nonce is bound to the install and can be consumed once. It is distinct from HMAC timestamp validation.

`GET /v1/me` provides only the install prefix, UTC creation/last-seen days, trust flag, and today's remaining skeleton/byte budgets and seconds to reset. The ledger day and `resetInSeconds` are derived from one captured instant, so the counters and reset remain consistent across UTC midnight. The install row and its ledger row are read in ONE statement filtered on the authenticated credential, so a credential that was withdrawn and whose UUID was re-enrolled never sees the replacement generation's dates or budget (it receives `401 unauthorized`). Accepted skeletons consume the skeleton allowance; ledger bytes consume the byte allowance. Defaults are 300 skeletons, 10 MiB, and 40 distinct batches per UTC day. The atomic `tryConsume` ledger operation enforces all three limits; the S4 ingest handler will use it for accepted work. Rejections and duplicates have separate counters.

Withdraw using a signed `DELETE /v1/installs/me` with no body. `202 {"status":"withdrawn","completionDeadline":"YYYY-MM-DD"}` confirms synchronous deletion of the install and its keyed rows. The deadline is tomorrow's UTC date and reserves a future asynchronous contract. After confirmation, erase local credentials and pending uploads. A lost response can be checked with `GET /v1/me`: `401 unauthorized` means the old credential no longer authenticates, though it does not by itself prove the reason. Revoked credentials receive `401 revoked`; cease uploads.

Honor `Retry-After` on `429 rate_limited`. Before timestamp checks, body reads, or database I/O, authenticated routes apply an admission token bucket keyed by the bearer’s unverified install ID string: capacity 120, refilling at 120/minute. Exhaustion returns `429 rate_limited` with `Retry-After: 1`. The synchronized LRU retains at most 10,000 IDs and never uses IP addresses. Enrollment shares one server-process bucket of 120/hour; authenticated traffic shares 60/minute per install, with an additional 30/hour per install for nonces. These in-memory buckets reset on restart. The per-IP edge limit is deferred to the custom-Caddy slice; the application never obtains, stores, or logs an IP address.

Server route authors: `InstallAuth` consumes the request channel once, with a 1 MiB limit, and places the verified raw bytes in `AuthenticatedBodyKey`. Decode that attribute; do not call `receive()` or `receiveChannel()` again. The verified identity is in `AuthenticatedInstallKey`. Only its eight-character prefix may appear in logs.
