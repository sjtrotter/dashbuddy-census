package cloud.trotter.census.server.ops

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 4648 unpadded, canonical base32; reject nonzero trailing bits. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun validSecret(secret: String): Boolean = secret.length in 16..64 && decode(secret) != null

    private fun decode(secret: String): ByteArray? {
        if (secret.isEmpty() || secret.length % 8 !in setOf(0, 2, 4, 5, 7)) return null
        var buffer = 0
        var bits = 0
        val bytes = mutableListOf<Byte>()
        for (char in secret) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes += (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) bytes.toByteArray() else null
    }

    /** Six-digit RFC 6238 HMAC-SHA1, with time supplied by the caller. */
    fun code(secret: String, epochSeconds: Long): String {
        val key = requireNotNull(decode(secret)) { "Invalid TOTP configuration" }
        require(epochSeconds >= 0)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(epochSeconds / 30).array())
        val offset = digest.last().toInt() and 15
        val number = ByteBuffer.wrap(digest, offset, 4).int and Int.MAX_VALUE
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    fun matches(secret: String, digits: String?, now: Instant): Boolean {
        if (digits == null || !Regex("[0-9]{6}").matches(digits)) return false
        val actual = digits.toByteArray(Charsets.US_ASCII)
        var matched = false
        for (offset in -1L..1L) {
            val seconds = now.epochSecond + offset * 30
            if (seconds >= 0) {
                val equal = MessageDigest.isEqual(code(secret, seconds).toByteArray(Charsets.US_ASCII), actual)
                matched = matched or equal
            }
        }
        return matched
    }
}

enum class TotpDecision { Accepted, Required, Replayed }

/** Atomic process-local replay admission; no code is exposed or logged. */
class TotpReplay {
    private val accepted = mutableMapOf<String, Instant>()

    @Synchronized
    fun verify(secret: String, digits: String?, now: Instant): TotpDecision {
        accepted.entries.removeIf { !it.value.isAfter(now) }
        if (!Totp.matches(secret, digits, now)) return TotpDecision.Required
        val code = requireNotNull(digits)
        if (accepted.keys.any { MessageDigest.isEqual(it.toByteArray(Charsets.US_ASCII), code.toByteArray(Charsets.US_ASCII)) }) {
            return TotpDecision.Replayed
        }
        accepted[code] = now.plusSeconds(90)
        return TotpDecision.Accepted
    }
}
