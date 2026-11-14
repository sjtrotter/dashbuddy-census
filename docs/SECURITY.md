# Security

Report vulnerabilities privately through the repository's GitHub **Security → Report a vulnerability** flow when enabled. If unavailable, ask a maintainer for a private reporting channel without posting exploit details or sensitive data publicly. Establish and test the reporting channel before production enrollment.

Scope includes authentication/enrollment, withdrawal, privacy leaks, ingest validation, k-anonymity and quarantine bypasses, database access, dependency/image integrity, and operator access. Identity enrollment, HMAC authentication, rotation, revocation storage, withdrawal, nonce issuance, and rate limits are implemented in S3. Ingest admission and operator HTTP controls remain future work, so the server is not yet ready for collecting app data.

The poisoning model assumes an attacker can enroll or control multiple installs and submit misleading skeletons. The only prize is promoting a token into the vocabulary, which needs at least k distinct installs past quarantine AND the human drafting gate. Per-install budgets, admission controls, replay protection, review, and eventual revocation must support that boundary; hashes alone neither prevent poisoning nor prove anonymity. S3 supplies identity, budget decisions, and ledger primitives; S4 must still implement atomic ingest admission and batch replay enforcement.

Store only the SHA-256 digest of the operator token in configuration. Use a high-entropy token and constant-time verification when authentication lands; the digest is not a substitute for token entropy. Never log bodies, bearer credentials, full install IDs, or client IPs. The schema permits reviewed plaintext vocabulary after the gate; trusted-envelope validation must still prevent below-threshold plaintext. Nonce issuance, revocation and deletion-tombstone timestamps (`withdrawals.withdrawn_at` and its copy `installs.erased_through`, 60 days) are the only sub-day data exceptions; enrolment is stored as a day (#79).

The operator browser surface exchanges the operator token and an enrolled TOTP code for a random `census_ops` session id; only its SHA-256 digest is held in process memory. `HttpOnly`, `Secure`, `SameSite=Strict`, and `Path=/ops` constrain cookie exposure and delivery, while a 12-hour lifetime, 1-hour idle timeout, and a single active session bound a stolen cookie's usefulness; a new login, logout, or process restart invalidates it. Theft can still authorize reads until invalidation, but mutations other than logout always require a fresh `X-Census-Totp` header, and login and mutations share replay protection. CSRF defenses combine `SameSite=Strict`, POST-only state changes, and `form-action 'self'` on the login page and dashboard. Login attempts spend the shared ops admission bucket before verification; wrong tokens do not consume codes. No token, TOTP code or secret, or cookie value is logged.

The release workflow signs image digests keylessly, produces an SBOM, and fails on CRITICAL vulnerabilities. Production operators must verify signatures, inspect the complete workflow result, and pin immutable digests. Review cloud logging, PostgreSQL/host logs, backup access, and 14-day deletion policies as part of deployment. AGPL-3.0-only applies to this server; the independently maintained contract remains Apache-2.0.

An authenticated endpoint rejects a bearer-only request without a valid body signature. **A leaked complete bearer nevertheless gives an attacker the HMAC key**, because its secret component is the signing secret: the attacker can generate signatures, impersonate the install, rotate its key, request nonces, or withdraw it until the key is rotated or the install is revoked. HMAC binds method, path, timestamp, and exact body bytes; it is not an independent second factor. Rotation immediately invalidates the old credential for subsequent authentication. A request already authenticated before rotation or revocation may still finish. The signature window allows replays for 300 seconds in either direction; batch-ID replay protection is S4 work.

For CLIENT credentials, PostgreSQL stores only `SHA-256(UTF-8(secret string))` in `installs.key_hash`, never the secret or bearer. The digest-only statement is scoped to those client bearers: the host holds the operator TOTP seed, the database password and the hash pepper as recoverable secrets (SSM SecureString → root-only `.env`), encrypted at rest, not hashed — disclosed in the README trust statement (#1180, #85). The encoded secret is decoded to key bytes for HMAC during each request and is not persisted. Authenticated raw bodies are retained only transiently in call attributes for route decoding. No part of the install ID appears in request logs, ingest logs or alarms (#79); authentication/purge summaries contain counts. Stored activity uses UTC dates; only nonce issuance, revocation and deletion tombstones have sub-day timestamps. Exposed SQL diagnostics are disabled because failure diagnostics can disclose full IDs or JSON. No application IP collection is used for rate limiting; per-IP edge enforcement lives in the Caddy edge (below) under the same logging stance.

Pre-authentication admission is process-local: a configurable global bucket allows a 600-token burst and refills at 600/minute across all IDs, before per-ID buckets with capacity/refill of 120/minute. Failed per-ID checks still spend a global token. A shared 32-permit semaphore bounds in-flight authentication from before body reading through the authentication decision; acquisition never waits and release is guaranteed by `finally`. Capacity exhaustion returns `429 rate_limited`. The 10,000-ID LRU evicts only the least-recently-used bucket with at least half its tokens remaining after refill. If none qualifies, a new ID is denied and counted in `capacityDenials`, preserving exhausted IDs under churn. An attacker who knows a victim ID can starve its 120/minute allowance; this fairness trade-off is accepted because the ID is a 128-bit random value and the authenticated per-install quota is separate. Aggregate exhaustion can also deny legitimate requests. No IP is collected; admission buckets reset on restart.

Exception events remain available for diagnosis. Logback's `SafeThrowableConverter` renders only the exception class-name chain and the first three stack frames of the outermost exception as `class.method:line`. It never renders exception messages, cause messages, or suppressed exceptions, and default full-stack rendering is disabled. Ordinary log messages must still exclude private data; SQL diagnostic loggers remain disabled. `Install.toString()` redacts the key hash and the ID.

Authentication captures one request instant for admission, timestamp verification, last-seen updates, and the route's ledger day/reset. Last-seen writes require the verified key hash and an active row and can only advance the stored day. `/v1/me` re-reads the stored row, so an older or stale request cannot report its attempted update as a successful write.

## Keyed storage (#85)

The phone's census word hash is public and unsalted on purpose, so every install agrees and k can be counted. Stored as it arrives, it would let anyone holding the database or a backup hash a guess list (place, store and first names) and see which words appear, and on which installs. The server therefore stores `HMAC-SHA256(pepper, label + hash)` in place of every word hash: token sightings, vocabulary rows and every slot `h` in stored samples. Values derived from word hashes are keyed the same way: a notification's fingerprint folds its title and text hashes into a public digest, so stored notification fingerprints, and every row that references one, are keyed too (`HMAC(pepper, public fingerprint)`). So are client batch ids in the ingest ledger: the app derives a skeleton batch id from the batch's public fingerprints and an envelope batch id from its bytes, so a raw id would confirm a guessed notification. A screen fingerprint covers class and id only and stays public. Keying happens at ingest before any write; V7 rewrote rows stored earlier, in one transaction, before the server served anything. Equality is unchanged, so k counting, constancy and cross-version comparison work as before.

The pepper is 32 random bytes held outside the database: an SSM SecureString on the AWS host, an `.env` secret for self-hosters. It never enters the database, a backup or a log line. Startup refuses a missing pepper, and refuses a pepper that differs from the one the database was keyed under (V7 stores an HMAC check value, not the pepper). It is not rotated: a new pepper is a new hash domain.

**What it protects:** a stolen database, dump or backup, read without the pepper. A guess list hashed the phone's way matches nothing in it (`KeyedStorageTest`). **What it does not protect:** the live server and whoever operates it. They hold the pepper and can hash, key and compare any guess; the vocabulary resolve route does exactly that (`HMAC(pepper, CensusHash.of(clearText))` against the stored value), and so does the anchor check, inside its limits ([below](#anchor-check-82)). Anyone who obtains both a dump and the pepper (the SSM parameter, the host's `.env`, or the operator's offline copy) is in the operator's position. Backups taken before V7 hold public hashes until they expire (14 days, plus 14 for S3 noncurrent versions). Stored values that stay unkeyed, and why: screen fingerprints (class and id, no text); trusted envelopes, which are redacted plaintext captures from the operator's own device (the trusted-install exception) and carry no census hashes, and the draft's `envelopeSha256`, a digest of one of them; withdrawal tombstones (`sha256` of a random install UUID, which a restore must recompute from the UUID and the off-host journal carries); credential digests of high-entropy secrets; reason codes, rule ids and counts. Install IDs at rest are covered next (#86).

## Epoch pseudonyms at rest (#86)

Rule authoring needs to know that k phones saw something; it never needs to follow one phone over time. Content rows (token sightings, cluster sightings, slot sightings (#80), per-install health) therefore carry an epoch pseudonym, `HMAC-SHA256(epoch key, purpose ‖ install id ‖ object)`, instead of the install id. An epoch is 30 UTC days on a fixed grid. One phone has the same pseudonym for the same object within an epoch, an unrelated one in the next, and unrelated ones for different tokens or clusters even within an epoch; per-install health has one pseudonym per epoch, because the alarms compare an install's own days. k is counted within one epoch and never by adding epochs, since one phone in two epochs is two unrelated pseudonyms. The install id stays in `installs`, `nonces` and `ingest_ledger` (authentication and quotas) and in `trusted_envelopes`, the operator's own devices' captures; a schema test and the startup shape check refuse it anywhere else.

Each epoch key is 32 random bytes, generated by the server and stored in `epoch_keys` wrapped with AES-GCM under a key derived from the pepper (the epoch number is authenticated, so a key cannot be moved to another epoch). It is not derived from the pepper, so deleting it destroys it. The server keeps the previous, current and next epochs' keys and deletes older ones.

**What it protects:** a dump or backup read without the pepper holds no usable epoch key and no install id beside any content row, so its rows cannot be tied to an install or to each other across epochs (`EpochPseudonymTest`). Once an epoch's key is deleted, nobody can tie that epoch's rows to an install, the operator included, after the last backup holding the wrapped key expires (14 days, plus 14 for S3 noncurrent versions). Erasure uses every key still stored, so an expired key's rows are erased until the sweep deletes the key. Unwrapped keys live only for the transaction that needs them (no thread-local or cache); the raw bytes are zeroed, but the JDK's `SecretKeySpec` and `Mac` keep internal copies until garbage collection. **The limit:** rows in the live epochs (the current and previous one, plus any row already tagged for tomorrow's) can be tied to an install by whoever holds their keys and the pepper, which is the live server and its operator: withdrawal, deletion and the restore replay work exactly that way, recomputing an install's pseudonyms. A dump together with the pepper is in the same position for the epochs whose keys it holds. Within an epoch, one install's health rows link to each other; a token or cluster row links to that install's other rows of the same object only. Each sighting also carries the install's `trusted` flag (the operator's own devices, linkable by design) and, during the install's first week only, the day its evidence starts to count (`counts_from`), which rows of one install share until the sweep clears it; both exist because k counting can no longer join the installs table. The ingest ledger keeps which days an install uploaded, for 7 days.

**Residual: transaction system columns.** PostgreSQL stamps every row with the id of the transaction that wrote it (`xmin`, a system column no query selects by default). Rows written together therefore share it: an install's health rows on both sides of an epoch boundary and its ledger row in one health upload, the cluster and token sightings of one skeleton batch beside that batch's install-keyed ledger update, and the rows one trust change updates. Anyone with live database access or the raw disk can use it to correlate those rows with each other and with the install id in the ledger. So the unlinkability of epoch pseudonyms is a guarantee against a stolen logical backup, not against someone who can read the live database or its files: a read-only SQL login, for example, gets these correlations without the pepper. We accept that limit at this scale: the database listens only inside the host's Compose network, and rows under destroyed keys are past retention. Backups are logical `pg_dump` output (`deploy/compose/backup.sh`), which carries no system columns, so a stolen backup has no such link.

**Residual: row order in a dump (#80, Sol S2).** `pg_dump` writes a table in heap order, roughly insertion order, so the rows one upload wrote would sit together in a backup and could be grouped as one install's without any key. `backup.sh` therefore passes the dump through `order-tagged-dump.sh`, which sorts the rows of every table carrying an `install_tag` column (token, cluster and slot sightings, per-install health) by that tag. A tag is an HMAC, so the order is independent of upload batches; rows that share a tag already belong together by design (one install's rows for one object within one epoch). `TaggedDumpOrderTest` dumps two installs' interleaved uploads with the real `pg_dump`, checks that no upload's rows stay together and that the ordered dump restores whole. A backup taken by any other path (an ad-hoc `pg_dump`) keeps heap order; use `backup.sh`. Backups taken before this change keep heap order until they expire (14 days, plus 14 for S3 noncurrent versions).

## Contributor tier (#81)

A contributor (ADR-0011 §15) is an install with a server-issued grant that may read slot classes, confirm a constant
slot's word as a hash, tag a field's role, send that word once its gate opens, and send a screen to the operator's
quarantine. It never sends an envelope or screen text, and a grant never makes an install trusted.

**Who can be a contributor.** A grant goes to any install enrolled at least 8 UTC days ago that asserts
`eligible: true`. The phone computes eligibility (opt-in, age, activity) and sends only the answer, so the server
cannot verify it; the age check is the server's own. An attacker who enrols installs and waits 8 days can hold grants.
Grants last 30 days; the operator revokes one (and its install's confirmations and role tags) from `/ops/contributors`,
and a revoked grant refuses new requests until cleared, a data deletion included.

**What a contributor can learn: the slot-class oracle.** Answered from live evidence, a class is a guessing oracle
(#80 review, Sol S1): with nine installs sharing a value, one install uploads a guess and reads `constant` exactly when
the guess is right. The released classes are built against it:

- A reader's answer never depends on its own rows. Classes come from the day's release, and each read leaves out the
  rows whose epoch tags are the reader's own, recomputed over the rows it reads, in the same REPEATABLE READ snapshot
  as the install, grant and release checks. An erasure deletes an install's released rows through the same tags, under
  a table lock the build also takes, so no interleaving leaves the reader's rows counted but unsubtracted (Sol N1). The
  `erased_through` marker is not needed for this: an erased install has no released rows left to subtract or to hide.
  A missing install, or a row in an epoch without a live key, fails closed (no answer, or that epoch's evidence left
  out).
- A release is served on its UTC day only and counts only rows inside retention on that day. A failed build leaves no
  release, never yesterday's (Sol N2).
- A release built on day D holds the slot rows as they stood at the end of D-1: a row first received on D is left out,
  and a row written again on D counts with the last day it had before (each slot sighting keeps its first and latest
  receipt days and its earlier last day). An upload therefore reaches another reader's answer on the next day at the
  earliest, whatever hour the day's build runs (Sol S6).
- The release is one consistent copy of those rows, so every presence it holds has a value behind it and the regional
  lower bound holds on it (Sol N3); a presence refreshed on D by a value received on D counts with its earlier last
  day, so it goes when its earlier values have.
- A reader is answered only about a variant it has uploaded itself (a live sighting of one of the slots it names), and
  the gate only for a slot it has a live sighting of; otherwise it reads an empty answer (Sol S3). Ownership is checked
  first, by the reader's own tags alone, and the not-owned path reads no peer evidence, so its timing cannot tell
  whether even one peer sent a guessed screen (a notification's fingerprint folds word hashes in; round 2). The gate
  reads other installs' confirmations only after both constant-at-k checks pass, so a refusal below k runs the same
  statements whether or not anyone else confirmed (round 2, S8). Tests compare the statement sequences.
- Only grant holders read, so a fresh install cannot be the reading half of a pair (Sol N4); reads are capped per
  install and day (200 class and gate items) and every read writes an audit row (grant, day, cluster, layout, items).
- Classes only; `unknown` (below k) is omitted; no hash or count is returned.

The word gate uses the same discipline: it opens for a value only when the asker confirmed it, two non-trusted installs
confirmed it within one epoch, and the slot is constant on it in both today's release and the live rows, each with the
asker's own rows left out. A plaintext word is refused until then, and the quarantine refuses an artifact carrying a
word whose gate is closed.

**What the classes and the gate are: an equality oracle restricted to constant values.** A class answers `constant`,
and a gate opens, only for a value one hash holds on at least 90% of at least k installs in that slot, counted within
one epoch with the asker left out. By construction that is the delivery app's own wording, shown on every dasher's
screen; revealing it is the purpose of the tier. What an attacker who holds a grant and has uploaded the screen can
learn: whether a guessed word equals a slot's constant value (by confirming it with a second install and asking the
gate), and whether a slot is constant, regional, varying, withheld or shape. What it cannot learn: any value of a
`varying` or `regional` slot (no class reveals one, and no gate opens on one), any value held by fewer than k installs,
any hash or count, or anything about a variant it has not uploaded. Customer names, addresses and codes are withheld on
the phone before anything is hashed (the chrome filter, ADR-0011 §2), so they reach no slot as a hash. The daily read,
review and word quotas cap the rate of guesses. Risk 12 still applies: a customer word common enough to be constant on
k installs can be mistaken for chrome, which is why a gated word reaches the operator's review and never the shipped
vocabulary.

**Residual: a ring of colluding contributors.** Leaving the reader out defeats one install, not several. Two
contributors past quarantine can split the work: one uploads a guess for a slot where k−1 others share a value, the
other reads that slot's class from the next day's release. That reveals one bit per uploader, slot and day (whether a
guess in a set of guesses completed a value k installs nearly share), at the cost of two aged installs with grants, a
day per answer and the daily quotas. The same pair can open the word gate for a guessed value, also no sooner than the next day's release; the word
then waits for the operator, who sees it before anything ships. We accept this at the current scale: the values reachable this way are ones about k installs already share
(chrome, a store or zone name, a very common first name), never one install's customer; the audit log shows two grants
working the same cluster; and revocation removes their confirmations. Necessary, not sufficient: two confirmations
and k installs do not prove a word is chrome (ADR-0011 risk 12), so a gated word reaches the operator's review, never
the shipped vocabulary.

**What the server keeps about contributors.** Confirmations and role tags carry epoch tags (one install's rows for two
slots are unrelated) and keyed hashes, never the word; the trusted flag rides on them so trusted installs never count.
Gated words are stored as text, unlinked to any install, until the operator resolves them, for at most 30 days.
`contributor_grants` names the install (a permission, like the quota ledger). The audit log names a grant, not an
install, but a grant maps to one install while it exists, so for up to 30 days it records which clusters one
contributor read. That is a contributor-only linkage the second disclosure must state (gig.log#1625); it goes with the
grant on withdrawal, and with every grant of the install on data deletion (a revoked grant stays as a bare revocation
marker, with no audit rows, Sol S5). Contributor quotas live on the install's daily ledger row (7 days).

**The quarantine.** An "Ask the developer" artifact is the skeleton the review screen described: validated like an
upload, so it holds no screen text, with only words whose gate is open. A key repeated anywhere in it is refused, and
the server seals only the validated artifact re-serialized canonically, never the bytes as received, so nothing the
validation did not see can be sealed (Sol S1). The submission share-locks the install row before the grant in the
transaction that writes, the row every erasure locks first, so an item never survives an erasure that raced it (Sol
S4). The server process sees the artifact while it validates it, as it sees any upload, then seals it to the
operator's X25519 public key (X25519, HKDF-SHA256,
AES-256-GCM bound to the item id, JDK only) and stores only the ciphertext. The private key stays on the operator's
workstation; nothing on the server, in the database or in a backup can open an item. Items expire within 72 hours, are
read only by the quarantine routes, and are never dumped by `backup.sh`. Encryption limits who reads an item after it
leaves the phone; it does not make customer text safe to send, which is why the artifact may carry none.

## Anchor check (#82)

**It is a dictionary lookup.** `POST /ops/anchor-check` takes a candidate word, hashes it the phone's way, keys it with
the pepper and compares it with the value a slot holds. That is the operation an attacker with the database and the
pepper would run to guess what the hashes hide (ADR-0011 §16). The route exists so drafting an anchor does not need
raw screen text; its limits are what keep it from being a way to guess customer text:

- **The chrome filter runs first, before anything is hashed or read.** A candidate must canonicalize to 1 to 40
  characters of 1 to 8 words with no digit (no phone number, code, PIN, house number, ZIP or price), carry no
  sensitive marker, `@`, web address or square bracket, and none of its case variants may have a name, address or code
  shape (a first name and an initial, a customer or code lead-in with a tail, a street line). The shapes read words
  and letter runs, never raw characters, so punctuation (`brandon c,`, `Deliver to: Sam`, `Main St!`) never hides one.
  A phrase with a capitalized word anywhere after its first word is refused as well (below). A query
  with any refused candidate is refused whole, as `not_chrome`, and none of its candidates is compared. The phone's own filter (ADR-0011 §2) uses PII shapes
  and customer markers that live in the app, outside the Apache-2.0 contract, so the server cannot run it; its check
  (`ChromeShape`) is built from the contract's predicates and a short server list of shapes, and errs toward refusing.
  The phone hashes a slot value only after its own full filter passed it, so a name that filter withholds is never a
  slot hash; a name it misses can be (risk 1).
- **Only constant slots answer.** A slot answers only when it is `constant` (one hash on 90% of the installs that saw
  it and on at least k of them, counted within one epoch, trusted and quarantined installs excluded). `unknown`,
  `regional`, `varying`, `withheld` and `shape` slots, and slots nobody uploaded, return the same `no_answer` as a
  wrong word on a constant slot, after the same statements (budget, the slot's live rows, the audit row) and the same
  hashing (each variant is compared against a placeholder whose result is discarded); tests compare the statement
  sequences. Neither the answer, the audit row nor the log names the slot's class.
- **Phrases are checked in lower and sentence case only.** Capitalization cannot tell app wording (`Accept Order`)
  from a person's name (`Riley Smith`, `Maria de Souza`, `Juan de la Cruz`), so the rule is plain (Sol rounds 2 and 3,
  S1): a phrase (two words or more) is refused if any word after its first starts with a capital, and an admitted
  phrase is compared only in its derived all-lower and sentence-case forms, never as sent. No compared phrase therefore
  has a capital after its first letter, so a lower-case candidate (`maria de souza`) cannot reach a slot that holds
  `Maria de Souza`. A single word is compared as sent, in lower and upper case and capitalized. Title-case and all-caps
  chrome is confirmed through contributor-approved vocabulary (#81)
  or on-phone probes (ADR-0011 §17) instead. These variants are all ones `hasText` accepts, since it ignores case and
  the hash keeps it. No fragment, prefix or pattern is ever hashed here; those
  questions go to phones as probes (ADR-0011 §17).
- **Budgets and audit.** At most 8 candidates per query and 200 per caller per UTC day, refused ones included. Every
  operator query also needs a fresh TOTP code (one use per code), so a stolen bearer token alone asks nothing, and the
  process-wide operator bucket (60 per minute) applies. Every query writes an audit row: caller, day, variant, slot,
  candidate count and answer class.

**The audit keeps no text.** A guess can be a name, and so can what a constant slot holds (below), so the server keeps
neither (Sol rounds 1 and 2, S2): the audit row records the caller, day, cluster key, variant, slot, candidate count
and answer class, and no candidate or matched text reaches the database, a backup or the log. The response returns
the matched text to the caller, who copies it into a draft. Rows name a caller (`operator`, later the agent's), never an install, keep 30 days, are never
read after that whether or not the sweep has run, and are in `backup.sh`'s dump. Logs carry the candidate count and
the answer class only.

**What it does not stop.**

- **Constancy is frequency evidence, not proof of app wording (risk 12).** A `constant` slot holds one hash on 90% of
  at least k installs. That says many installs saw the same value there; it does not say the delivery app wrote it. A
  store name, a place name or a very common first name can be constant on k honest installs, and a match on one is
  evidence for a draft, never approval: the regional class, the audit and the human review before anything ships are
  the controls.
- **Sybils can make a victim's value constant (risk 5).** Someone with k − 1 aged installs of their own can upload a
  guessed value (a customer's name) to a slot that one victim's install shows. With the victim's row the slot holds the
  guess on every install that saw it, turns `constant`, and the check answers `match`; without it, the slot stays
  below k and answers `no_answer`. So the check can confirm one install's value. What bounds this: the route is
  operator-only (a bearer token and a fresh TOTP code per query); it refuses name-, address- and code-shaped
  candidates and never compares a phrase with a capital after its first letter; the candidates
  spend a daily budget; and nothing about the query's text is stored. What it does not remove: a name that passes the
  filter (a lone first name, `Riley`, or a name a screen renders in lower or sentence case), confirmed by someone holding the
  operator credentials and k − 1 aged installs. The cluster page's class and counts give that same person the same
  answer without the check, so the check adds speed, not a new answer.
- **Risk 14, the tool binds the tool.** These limits bind this route, the dashboard and the agent token (#84). The
  live operator holds the database and the pepper and can compare any guess outside the route (risk 7).

## Edge admission (#1178)

Per-client-address limits run in the Caddy edge (`caddy-ratelimit`, in-memory zones, no access log, no retention): `/v1/enroll` 10/min and 20/h, all of `/v1` 200/min. The plugin's refusal log line carries the peer address and is discarded by the Caddyfile's global default logger (the site-level discards do not cover it); the address lives in process memory for one window plus the 15 s sweep. Under source rotation the limiter's own memory is the failure mode (~2 KB per address until swept; tens of thousands of addresses per window before the 96 MiB cgroup restarts Caddy), which needs a real IPv4 pool and is accepted at this scale; the key is the bare peer address, so an IPv6 /64 would evade it — revisit before any AAAA record. The edge image is published by `.github/workflows/caddy-image.yml` on `caddy-v*` tags and keyless-signed; verify with the identity `https://github.com/sjtrotter/dashbuddy-census/.github/workflows/caddy-image.yml@refs/tags/caddy-vX.Y.Z`, and pin production to the verified digest in the committed Compose file. They bound the cheapest abuse — invented install ids spending the application's shared enrol/ingest buckets — without the server ever obtaining, storing or logging an IP; the zones are the only place an address exists and they live in process memory for one window. Not a defence against a distributed source, which is accepted at this scale.
