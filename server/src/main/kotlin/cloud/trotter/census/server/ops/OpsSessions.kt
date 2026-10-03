package cloud.trotter.census.server.ops

import cloud.trotter.census.server.auth.hashSecret
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Process-local, single active operator session. Only the SHA-256 of the id is held; the raw id lives in the cookie. */
class OpsSessions(
    private val ttl: Duration = Duration.ofHours(12),
    private val idle: Duration = Duration.ofHours(1),
    private val random: SecureRandom = SecureRandom(),
) {
    private class Session(val idHash: ByteArray, val createdAt: Instant, var lastSeen: Instant)
    private var current: Session? = null
    private val rawPattern = Regex("[A-Za-z0-9_-]{43}")

    /** Mints a fresh session, replacing any existing one (one operator, one browser). Returns the raw cookie value. */
    @Synchronized
    fun create(now: Instant): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        current = Session(hashSecret(raw).toByteArray(Charsets.US_ASCII), now, now)
        return raw
    }

    /** Constant-time check; a hit refreshes lastSeen. Absent, malformed, expired or idle sessions fail closed. */
    @Synchronized
    fun validate(raw: String?, now: Instant): Boolean {
        if (raw == null || raw.length != 43 || !rawPattern.matches(raw)) return false
        val session = current ?: return false
        if (!now.isBefore(session.createdAt.plus(ttl)) || !now.isBefore(session.lastSeen.plus(idle))) {
            current = null
            return false
        }
        if (!MessageDigest.isEqual(hashSecret(raw).toByteArray(Charsets.US_ASCII), session.idHash)) return false
        session.lastSeen = now
        return true
    }

    @Synchronized
    fun revoke() { current = null }
}
