package cloud.trotter.census.server.auth

import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallIdGrammar
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.census.contract.auth.RequestSigner as ContractRequestSigner
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** the contract is the ONE owner of the wire-auth bytes; this file adapts it to Ktor types */
@JvmInline
value class InstallId private constructor(val value: String) {
    fun toUuid(): UUID = UUID.fromString(value)

    companion object {
        fun parse(value: String): InstallId? = if (InstallIdGrammar.isCanonicalV4(value)) InstallId(value) else null
    }
}

/** Deliberately not a data class: toString must not disclose the credential. */
class BearerCredential(val installId: InstallId, val secret: String) {
    override fun toString(): String = "BearerCredential([redacted])"
}

fun isValidSecret(secret: String): Boolean = InstallSecret.isValid(secret)

fun parseBearer(header: String?): BearerCredential? {
    val credential = Bearer.parse(header) ?: return null
    val id = InstallId.parse(credential.installId) ?: return null
    return BearerCredential(id, credential.secret)
}

fun hashSecret(secret: String): String = InstallSecret.hash(secret)

// A plain digest helper for server-side uses (nonces, state): not wire-auth, so it does not route through the contract.
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

// Retained for server nonce generation, which is independent of wire signing.
internal fun ByteArray.toLowerHex(): String = HexFormat.of().formatHex(this)

/** Server adapter for the contract's wire algorithm; see docs/CLIENT.md. */
object RequestSigner {
    fun canonical(method: String, path: String, timestamp: String, rawBody: ByteArray = byteArrayOf()): String =
        ContractRequestSigner.canonical(method, path, timestamp, rawBody)

    fun sign(secret: String, canonical: String): String = ContractRequestSigner.sign(secret, canonical)

    fun verify(secret: String, canonical: String, header: String): Boolean =
        ContractRequestSigner.verify(secret, canonical, header)

    /** The contract's internal seam is not visible across modules; retain shape checks and an injectable comparator. */
    internal fun verify(
        secret: String,
        canonical: String,
        header: String,
        equal: (ByteArray, ByteArray) -> Boolean = { expected, actual -> MessageDigest.isEqual(expected, actual) },
    ): Boolean {
        if (header.length != 67 || !header.startsWith("v1=") || header.drop(3).any { it !in "0123456789abcdef" }) return false
        return equal(ContractRequestSigner.sign(secret, canonical).toByteArray(Charsets.US_ASCII), header.toByteArray(Charsets.US_ASCII))
    }

    fun timestampInWindow(timestamp: String, now: Instant): Boolean =
        ContractRequestSigner.timestampInWindow(timestamp, now.epochSecond)
}
