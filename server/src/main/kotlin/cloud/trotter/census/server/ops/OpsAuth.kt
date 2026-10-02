package cloud.trotter.census.server.ops

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.SystemClock
import cloud.trotter.census.server.auth.hashSecret
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.security.MessageDigest

class OpsAuthConfig {
    var config: Config? = null
    var clock: Clock = SystemClock
}

val OpsAuth = createRouteScopedPlugin("OpsAuth", ::OpsAuthConfig) {
    val config = requireNotNull(pluginConfig.config)
    val clock = pluginConfig.clock
    val replay = TotpReplay()
    val bucket = OpsBucket()
    onCall { call ->
        if (call.isHandled) return@onCall
        // Review (Astra, S6 round 1): Ktor's route-scoped RateLimit runs AFTER this hook and skips handled calls, so a
        // 401 never spent a token — unlimited TOTP guesses with a stolen bearer. Admission is decided FIRST, here, and
        // a refused request never reaches the TOTP verifier (so a valid code is never consumed by a 429).
        if (!bucket.admit(clock.now())) {
            call.response.headers.append(HttpHeaders.RetryAfter, "1")
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate_limited"))
            return@onCall
        }
        val header = call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
        if (token.isNullOrEmpty() || !MessageDigest.isEqual(
                hashSecret(token).toByteArray(Charsets.US_ASCII), config.operatorTokenSha256.toByteArray(Charsets.US_ASCII),
            )
        ) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@onCall
        }
        if (call.request.httpMethod.value !in setOf("GET", "HEAD", "OPTIONS")) {
            val secret = config.operatorTotpSecret
            if (secret == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("totp_unconfigured"))
                return@onCall
            }
            when (replay.verify(secret, call.request.headers["X-Census-Totp"], clock.now())) {
                TotpDecision.Accepted -> Unit
                TotpDecision.Required -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("totp_required"))
                TotpDecision.Replayed -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("totp_replayed"))
            }
        }
    }
}

internal fun isOpsPath(path: String): Boolean = path == "/ops" || path.startsWith("/ops/")

/** Unknown first segments are also suppressed: an arbitrary path segment can itself be a credential. */
internal fun opsLogPath(path: String): String {
    val segment = path.removePrefix("/ops").trimStart('/').substringBefore('/')
    return if (segment in setOf("clusters", "installs", "health", "alarms", "ledger", "vocabulary")) "/ops/$segment" else "/ops"
}

/** Application scope includes routing failures; CallLogging excludes these to produce exactly one INFO line. */
val OpsLogging = createApplicationPlugin("OpsLogging") {
    val log = LoggerFactory.getLogger("Ops")
    on(ResponseSent) { call ->
        if (isOpsPath(call.request.path())) {
            val method = call.request.httpMethod.value.takeIf { it in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") } ?: "OTHER"
            log.info("ops method={} path={} status={}", method, opsLogPath(call.request.path()), call.response.status()?.value ?: 0)
        }
    }
}

/**
 * One process-wide token bucket for every `/ops` request, successful or not: 60 tokens, refilled at 60/minute.
 * Integer millitoken accounting (Astra, S6 round 2): exactly one token at T + 1 s, no floating-point undershoot; a
 * clock that moves BACKWARDS re-bases the refill anchor instead of freezing it (the ops clock is wall time).
 */
class OpsBucket(private val capacity: Int = 60, private val perMinute: Int = 60) {
    private var milliTokens: Long = capacity * 1_000L
    private var updatedAt: java.time.Instant? = null

    @Synchronized
    fun admit(now: java.time.Instant): Boolean {
        val last = updatedAt
        if (last == null || now.isBefore(last)) {
            updatedAt = now
        } else {
            val elapsedMillis = java.time.Duration.between(last, now).toMillis()
            if (elapsedMillis > 0) {
                milliTokens = (milliTokens + elapsedMillis * perMinute / 60).coerceAtMost(capacity * 1_000L)
                updatedAt = now
            }
        }
        if (milliTokens < 1_000L) return false
        milliTokens -= 1_000L
        return true
    }
}
