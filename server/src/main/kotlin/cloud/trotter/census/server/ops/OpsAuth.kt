package cloud.trotter.census.server.ops

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SystemClock
import cloud.trotter.census.server.auth.BODY_READ_TIMEOUT_MS
import cloud.trotter.census.server.auth.BoundedRead
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.auth.readBounded
import cloud.trotter.census.server.today
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.isHandled
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import org.slf4j.LoggerFactory

val OpsForm = AttributeKey<Parameters>("OpsForm")

class OpsAuthConfig {
    var config: Config? = null
    var clock: Clock = SystemClock
    var sessions: OpsSessions? = null
    var replay: TotpReplay? = null
}

val OpsAuth = createRouteScopedPlugin("OpsAuth", ::OpsAuthConfig) {
    val config = requireNotNull(pluginConfig.config)
    val clock = pluginConfig.clock
    val sessions = requireNotNull(pluginConfig.sessions)
    val replay = requireNotNull(pluginConfig.replay) { "OpsAuth needs the TotpReplay shared with the login route" }
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
        val path = call.request.path()
        val method = call.request.httpMethod
        // Login retains its raw 4 KiB cap and original parser, authenticating its token before TOTP.
        if (path == "/ops/login" && method in setOf(HttpMethod.Get, HttpMethod.Post)) {
            if (method == HttpMethod.Post && call.request.contentType().withoutParameters() == ContentType.Application.FormUrlEncoded) {
                if (!call.readOpsForm()) return@onCall
            }
            return@onCall
        }
        val header = call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
        // An explicit bad Authorization header must never fall back to a session cookie.
        val authenticated = if (header != null) {
            !token.isNullOrEmpty() && config.operatorTokenMatches(token)
        } else {
            sessions.validate(call.request.cookies["census_ops"], clock.now())
        }
        if (!authenticated) {
            // A browser landing on /ops without a session is sent to the form; API callers keep the JSON 401.
            if (header == null && method == HttpMethod.Get && call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append(HttpHeaders.Location, "/ops/login")
                call.respond(HttpStatusCode.SeeOther)
            } else {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            }
            return@onCall
        }
        // Everything an authenticated operator reads may now render in a browser: never cache it past logout.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (method == HttpMethod.Post && isDraftFormPath(path) &&
            call.request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) {
            throw BadRequestException("Expected operator form")
        }
        if (method == HttpMethod.Post && call.request.contentType().withoutParameters() == ContentType.Application.FormUrlEncoded) {
            if (!call.readOpsForm()) return@onCall
        }
        val form = call.attributes.getOrNull(OpsForm)
        val headerCode = call.request.headers["X-Census-Totp"]
        if (headerCode != null && form?.contains("totp") == true) {
            if (isOpsHtmlPath(path)) {
                call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
                call.respondText(opsPage(headerContent = { metadata(config.serverVersion, Policy().k, clock.today().toString()) }) {
                    emptyState("totp_conflict")
                }, ContentType.Text.Html, HttpStatusCode.BadRequest)
            } else call.respond(HttpStatusCode.BadRequest, ErrorResponse("totp_conflict"))
            return@onCall
        }
        if (method == HttpMethod.Post && isPureDraftPath(path)) return@onCall
        if (method.value !in setOf("GET", "HEAD", "OPTIONS") && !(path == "/ops/logout" && method == HttpMethod.Post)) {
            val secret = config.operatorTotpSecret
            if (secret == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("totp_unconfigured"))
                return@onCall
            }
            when (replay.verify(secret, headerCode ?: form?.get("totp"), clock.now())) {
                TotpDecision.Accepted -> Unit
                TotpDecision.Required -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("totp_required"))
                TotpDecision.Replayed -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("totp_replayed"))
            }
        }
    }
}

/** Shared constant-time token verification for bearer requests and the login form. */
internal fun Config.operatorTokenMatches(token: String): Boolean = MessageDigest.isEqual(
    hashSecret(token).toByteArray(Charsets.US_ASCII), operatorTokenSha256.toByteArray(Charsets.US_ASCII),
)

internal fun isOpsPath(path: String): Boolean = path == "/ops" || path.startsWith("/ops/")

/** The ops paths that answer a browser page rather than JSON: a request-level failure on them must answer HTML too. */
/**
 * Judged on routing's DECODED segments so the answer agrees with the route that would have run: exactly the
 * home page, the review page and a one-segment cluster detail page. An undecodable path answers false (JSON).
 * `/ops/login` deliberately stays out: its form rejections answer the JSON envelope (pinned by OpsLoginTest).
 */
internal fun isOpsHtmlPath(path: String): Boolean {
    val segments = decodedOpsSegments(path) ?: return false
    return segments.isEmpty() ||
        (segments.size == 2 && segments[0] == "clusters" && segments[1] == "view") ||
        (segments.size == 3 && segments[0] == "clusters" && segments[2] in setOf("view", "draft")) ||
        (segments.size == 4 && segments[0] == "clusters" && segments[2] == "draft" && segments[3] in setOf("preview", "shape"))
}

/** Unknown first segments are also suppressed: an arbitrary path segment can itself be a credential. */
internal fun opsLogPath(path: String): String {
    val segment = path.removePrefix("/ops").trimStart('/').substringBefore('/')
    return if (segment in setOf("clusters", "installs", "health", "alarms", "ledger", "vocabulary", "login", "logout")) "/ops/$segment" else "/ops"
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

/** Only routed, decoded preview/shape paths are pure; extra segments and other verbs retain TOTP. */
internal fun isPureDraftPath(path: String): Boolean = isDraftFormPath(path, pureOnly = true)

private fun decodedOpsSegments(path: String): List<String>? {
    if (!isOpsPath(path)) return null
    return try {
        path.removePrefix("/ops").split('/').drop(1).map { it.decodeURLPart() }.filter { it.isNotEmpty() }
    } catch (_: URLDecodeException) { null }
}

private fun isDraftFormPath(path: String, pureOnly: Boolean = false): Boolean {
    val segments = decodedOpsSegments(path) ?: return false
    return segments.size in 3..4 && segments[0] == "clusters" && fingerprintPattern.matches(segments[1]) &&
        segments[2] == "draft" && (if (segments.size == 4) segments[3] in setOf("preview", "shape") else !pureOnly)
}

/** Explicit bounds, including duplicates after decoding. Unknown fields still consume the parser budget. */
internal fun parseOpsForm(body: String, draftLimits: Boolean = true): Parameters {
    val fields = if (body.isEmpty()) emptyList() else body.split('&')
    if (draftLimits && fields.size > 1000) throw BadRequestException("Invalid operator form")
    val builder = ParametersBuilder()
    for (field in fields) {
        val key: String
        val value: String
        try {
            key = field.substringBefore('=').replace('+', ' ').decodeURLPart()
            value = field.substringAfter('=', "").replace('+', ' ').decodeURLPart()
        } catch (_: URLDecodeException) { throw BadRequestException("Invalid operator form") }
        catch (_: IllegalArgumentException) { throw BadRequestException("Invalid operator form") }
        if ((draftLimits && value.length > 2000) || builder.contains(key)) throw BadRequestException("Invalid operator form")
        builder.append(key, value)
    }
    return builder.build()
}

private suspend fun ApplicationCall.readOpsForm(): Boolean {
    val draft = isDraftFormPath(request.path())
    when (val read = readBounded(receiveChannel(), if (draft) 64 * 1024 else 4096, BODY_READ_TIMEOUT_MS)) {
        is BoundedRead.Ok -> {
            val body = read.bytes.toString(Charsets.UTF_8)
            val fields = if (request.path() != "/ops/login") parseOpsForm(body, draftLimits = draft) else try {
                body.replace('+', ' ').parseUrlEncodedParameters()
            } catch (_: IllegalArgumentException) { throw BadRequestException("Invalid operator form") }
            attributes.put(OpsForm, fields)
        }
        BoundedRead.TooLarge -> {
            if (!draft) throw BadRequestException("Invalid operator form")
            respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large")); return false
        }
        BoundedRead.Timeout -> { respond(HttpStatusCode.RequestTimeout, ErrorResponse("request_timeout")); return false }
    }
    return true
}
