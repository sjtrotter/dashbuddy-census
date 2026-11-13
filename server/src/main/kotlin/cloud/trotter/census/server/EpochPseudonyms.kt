package cloud.trotter.census.server

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Epochs for the at-rest pseudonyms (#86, ADR-0011 §21). An epoch is 30 whole UTC days on a fixed grid: epoch `n`
 * covers the days `[30n, 30n + 29]` counted from 1970-01-01, so every server and every test agrees on the boundaries
 * without configuration. A row is tagged under the epoch of the day it describes (the observation or report day), so
 * one install's rows for one day always carry one tag.
 */
object Epochs {
    const val DAYS: Int = 30

    fun of(day: LocalDate): Int = Math.floorDiv(day.toEpochDay(), DAYS.toLong()).toInt()

    fun firstDay(epoch: Int): LocalDate = LocalDate.ofEpochDay(epoch.toLong() * DAYS)

    /**
     * The epochs whose keys may exist on [today]: the previous, the current and the next (a report may name tomorrow,
     * and the day after a boundary still accepts observations from the day before it). Older keys are destroyed.
     */
    fun live(today: LocalDate): IntRange = of(today).let { (it - 1)..(it + 1) }
}

/** What a tag pseudonymises; each purpose is its own label, so tags of different purposes never collide (#86). */
enum class TagPurpose(val label: String) {
    /** A token sighting: the object is `hashDomain:filterRev:keyedTokenHash`. */
    TOKEN("token"),

    /** A cluster sighting: the object is the stored cluster key. */
    CLUSTER("cluster"),

    /** Per-install health: no object, so the alarms can read one install's own history within the epoch. */
    HEALTH("health"),
}

/**
 * One epoch's random key (#86). `tag(purpose, install, object)` is `HMAC-SHA256(key, label ‖ purpose ‖ install ‖
 * object)`: the same install gets the same tag for the same object within one epoch and an unrelated tag in the next.
 * Because the object is inside the MAC, one install's rows for different tokens or clusters carry different tags,
 * so even within an epoch a dump does not show which tokens one install saw together.
 *
 * Only a holder of this key can recompute a tag from an install id. The key is random, never derived from the pepper,
 * so destroying it ([cloud.trotter.census.server.db.EpochKeyStore]) makes its rows permanently unlinkable.
 *
 * Key material in memory: an instance is unwrapped for one transaction (or one migration run) and never cached,
 * shared across threads or kept in a thread-local, so nothing outlives the work that needed it; once it is
 * unreachable the key goes with the next collection. The raw bytes this class handles are zeroed as soon as the
 * [SecretKeySpec] holds them. The JVM limit: [SecretKeySpec] and the initialised [Mac] keep their own copies, which the
 * JDK offers no reliable way to wipe, so those copies live until garbage collection reclaims them.
 */
class EpochKey internal constructor(val epoch: Int, private val key: SecretKeySpec) {
    // One Mac per instance, created on first use; an instance belongs to one transaction, the lock covers misuse.
    private var mac: Mac? = null

    @Synchronized
    fun tag(purpose: TagPurpose, installId: UUID, objectKey: String): String {
        val mac = mac ?: Mac.getInstance(MAC).apply { init(key) }.also { mac = it }
        return HexFormat.of().formatHex(mac.doFinal("$TAG_LABEL${purpose.label}:$installId:$objectKey".toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "EpochKey(epoch=$epoch, [redacted])"

    companion object {
        /** A tag is the full HMAC-SHA256 in lowercase hex. */
        const val TAG_HEX_LENGTH: Int = 64
        private const val MAC = "HmacSHA256"
        private const val TAG_LABEL = "census.epoch.v1:tag:"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val WRAP = "AES/GCM/NoPadding"
        private val random = SecureRandom()

        /** A fresh random key for [epoch], wrapped for storage under [keying]'s epoch wrapping key. */
        fun generate(keying: HashKeying, epoch: Int): Pair<EpochKey, ByteArray> {
            val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            try {
                val cipher = Cipher.getInstance(WRAP).apply {
                    init(Cipher.ENCRYPT_MODE, keying.epochWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                    updateAAD(aad(epoch))
                }
                return EpochKey(epoch, SecretKeySpec(raw, MAC)) to (iv + cipher.doFinal(raw))
            } finally {
                raw.fill(0)
            }
        }

        /**
         * Unwraps a stored key. The epoch is authenticated data, so a wrapped key moved to another epoch's row fails;
         * so does one wrapped under another pepper. Either is [EpochKeyUnwrapException], which carries no message.
         */
        fun unwrap(keying: HashKeying, epoch: Int, wrapped: ByteArray): EpochKey {
            if (wrapped.size != IV_BYTES + KEY_BYTES + GCM_TAG_BITS / 8) throw EpochKeyUnwrapException()
            val raw = try {
                Cipher.getInstance(WRAP).apply {
                    init(Cipher.DECRYPT_MODE, keying.epochWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, IV_BYTES))
                    updateAAD(aad(epoch))
                }.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
            } catch (_: GeneralSecurityException) {
                throw EpochKeyUnwrapException()
            }
            try {
                return EpochKey(epoch, SecretKeySpec(raw, MAC))
            } finally {
                raw.fill(0)
            }
        }

        private fun aad(epoch: Int): ByteArray = "census.epoch.v1:key:$epoch".toByteArray(Charsets.UTF_8)
    }
}

/** A stored epoch key that does not open under the configured pepper; deliberately carries no message (#86). */
class EpochKeyUnwrapException : IllegalStateException()
