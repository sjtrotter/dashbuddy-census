package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed storage (#85, ADR-0011 §3 amendment and §21). The phone's census hash is public and unsalted, so a stolen
 * database or backup could be guessed against offline: hash a list of names, see which ones appear. The server
 * therefore stores `HMAC-SHA256(pepper, label + value)` in place of every word hash and every value derived from one,
 * where the pepper is a server secret held outside the database, its backups and the logs.
 *
 * Equality is preserved (one pepper, deterministic), so k counting, constancy and cross-version comparison work on
 * the keyed values exactly as on the public ones. A keyed word hash keeps the census width ([CensusHash.HEX_LENGTH]
 * hex, 64 bits, the same collision budget as the public hash) so it fits every column and contract slot that held a
 * public one; a keyed notification fingerprint keeps the full [CensusFingerprint.HEX_LENGTH]. Each kind has its own
 * label, so a keyed token never equals a keyed fingerprint or the [keyCheck].
 *
 * This protects data at rest only. The live server, and the operator who runs it, hold the pepper and can still
 * test a guess (the vocabulary resolve route does exactly that, [candidate]).
 *
 * The pepper is never rotated: a new pepper is a new hash domain, and every stored value would have to be rewritten
 * under it. [keyCheck] lets startup refuse a pepper that differs from the one the database was keyed under.
 */
class HashKeying private constructor(private val key: SecretKeySpec) {
    /** The keyed form of a public census word hash (16 lowercase hex in, 16 lowercase hex out). */
    fun token(censusHash: String): String {
        require(CensusHash.isWellFormed(censusHash)) { "not a census hash" }
        return mac(TOKEN_LABEL, censusHash).substring(0, CensusHash.HEX_LENGTH)
    }

    /**
     * The keyed form of a fingerprint that folds word hashes in (today: a notification's). Ingest applies it to the
     * fingerprint recomputed over the already-keyed slots ([cloud.trotter.census.server.ingest.storedFingerprint]),
     * so a stored body proves its own key.
     */
    fun fingerprint(fingerprint: String): String {
        require(CensusFingerprint.isWellFormed(fingerprint)) { "not a census fingerprint" }
        return mac(FINGERPRINT_LABEL, fingerprint)
    }

    /**
     * The keyed form of a client batch id. The app derives it from the batch's public fingerprints (a truncated
     * sha256), so a stored raw id would let a guessed notification be confirmed against the ledger.
     */
    fun batch(batchId: String): String {
        require(batchId.isNotEmpty() && batchId.length <= MAX_BATCH_ID_LENGTH) { "not a batch id" }
        return mac(BATCH_LABEL, batchId)
    }

    /**
     * An operator-supplied plaintext candidate as the server stores it: the public census hash of its canonical
     * form, then keyed. Null when the text has no canonical form.
     */
    fun candidate(text: String): String? = CensusHash.of(text)?.let(::token)

    /** Constant-time equality of two keyed values. */
    fun same(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /** A value that identifies the pepper without revealing it; stored in `hash_keying` by V7. */
    val keyCheck: String get() = mac(CHECK_LABEL, "")

    // A batch keys thousands of slots; a Mac is not thread-safe, so each thread keeps one (doFinal resets it).
    private val macs = ThreadLocal.withInitial { Mac.getInstance(ALGORITHM).apply { init(key) } }

    private fun mac(label: String, value: String): String =
        HexFormat.of().formatHex(macs.get().doFinal((label + value).toByteArray(Charsets.UTF_8)))

    override fun toString(): String = "HashKeying([redacted])"

    companion object {
        /** The environment variable holding the pepper: exactly [PEPPER_HEX_LENGTH] hex digits (32 random bytes). */
        const val ENV: String = "CENSUS_HASH_PEPPER"
        const val PEPPER_HEX_LENGTH: Int = 64
        private const val ALGORITHM = "HmacSHA256"
        private const val TOKEN_LABEL = "census.keyed.v1:token:"
        private const val FINGERPRINT_LABEL = "census.keyed.v1:fingerprint:"
        private const val BATCH_LABEL = "census.keyed.v1:batch:"
        private const val CHECK_LABEL = "census.keyed.v1:check"
        private const val MAX_BATCH_ID_LENGTH = 128

        /** Parses the pepper; the error names the variable, never the value. */
        fun fromHex(hex: String): HashKeying {
            require(hex.length == PEPPER_HEX_LENGTH && hex.all { it.isHexDigit() }) { "Invalid variable: $ENV" }
            return HashKeying(SecretKeySpec(HexFormat.of().parseHex(hex), ALGORITHM))
        }
    }
}
