package cloud.trotter.census.server.auth

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@JvmInline
value class InstallId private constructor(val value: String) {
    fun toUuid(): UUID = UUID.fromString(value)

    companion object {
        private val canonicalV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        fun parse(value: String): InstallId? = if (canonicalV4.matches(value)) InstallId(value) else null
    }
}

/** Deliberately not a data class: toString must not disclose the credential. */
class BearerCredential(val installId: InstallId, val secret: String) {
    override fun toString(): String = "BearerCredential([redacted])"
}

private val secretPattern = Regex("[A-Za-z0-9_-]{43,128}")

fun isValidSecret(secret: String): Boolean {
    if (!secretPattern.matches(secret)) return false
    val bytes = try {
        Base64.getUrlDecoder().decode(secret)
    } catch (_: IllegalArgumentException) {
        return false
    }
    return bytes.size >= 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == secret // constant-time: not credential material
}

fun parseBearer(header: String?): BearerCredential? {
    if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return null
    val credential = header.substring(7)
    val separator = credential.indexOf('.')
    if (separator != 36) return null
    val id = InstallId.parse(credential.substring(0, separator)) ?: return null
    val secret = credential.substring(separator + 1)
    return if (isValidSecret(secret)) BearerCredential(id, secret) else null
}

fun hashSecret(secret: String): String = sha256Hex(secret.toByteArray(Charsets.UTF_8))

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

internal fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Shared wire algorithm; see docs/CLIENT.md. HMAC uses the base64url-decoded secret bytes. */
object RequestSigner {
    fun canonical(method: String, path: String, timestamp: String, rawBody: ByteArray = byteArrayOf()): String =
        "$method\n${path.substringBefore('?')}\n$timestamp\n${sha256Hex(rawBody)}"

    fun sign(secret: String, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
        return "v1=" + mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    fun verify(secret: String, canonical: String, header: String): Boolean =
        verify(secret, canonical, header) { expected, actual -> MessageDigest.isEqual(expected, actual) }

    /** Test seam checks that well-formed signatures take the constant-time comparison path. */
    internal fun verify(secret: String, canonical: String, header: String, equal: (ByteArray, ByteArray) -> Boolean): Boolean {
        if (header.length != 67 || !header.startsWith("v1=") || header.drop(3).any { it !in "0123456789abcdef" }) return false
        return equal(sign(secret, canonical).toByteArray(Charsets.US_ASCII), header.toByteArray(Charsets.US_ASCII))
    }

    fun timestampInWindow(timestamp: String, now: java.time.Instant): Boolean {
        if (!Regex("-?(0|[1-9][0-9]*)").matches(timestamp)) return false
        val seconds = timestamp.toLongOrNull() ?: return false
        return seconds >= now.epochSecond - 300 && seconds <= now.epochSecond + 300
    }
}
