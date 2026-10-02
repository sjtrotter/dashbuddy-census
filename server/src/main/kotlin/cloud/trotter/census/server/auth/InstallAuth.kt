package cloud.trotter.census.server.auth

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.SystemClock
import cloud.trotter.census.server.db.Install
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.RevokedInstallException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/** Only this verified prefix may reach CallLogging. Never retain the secret in attributes. */
data class AuthenticatedInstall(val id: String, val prefix: String, val secretHash: String)

val AuthenticatedInstallKey = AttributeKey<AuthenticatedInstall>("AuthenticatedInstall")
val AuthenticatedBodyKey = AttributeKey<ByteArray>("AuthenticatedBody")
val InstallRowKey = AttributeKey<Install>("InstallRow")

object AuthStats {
    val ok = AtomicLong()
    val badBearer = AtomicLong()
    val badSignature = AtomicLong()
    val skew = AtomicLong()
    val revoked = AtomicLong()
    val unknownInstall = AtomicLong()
    private val decisions = AtomicLong()
    private val log = LoggerFactory.getLogger("census.auth")

    internal fun record(counter: AtomicLong) {
        counter.incrementAndGet()
        if (decisions.incrementAndGet() % 500L == 0L) {
            log.info(
                "auth ok={} bad_bearer={} bad_signature={} skew={} revoked={} unknown_install={}",
                ok.get(), badBearer.get(), badSignature.get(), skew.get(), revoked.get(), unknownInstall.get(),
            )
        }
    }
}

class InstallAuthConfig {
    var store: InstallStore? = null
    var clock: Clock = SystemClock
}

/**
 * Runs in Ktor's Plugins phase before rate-limit Validators.
 * Routes MUST decode AuthenticatedBodyKey, not receive(): the channel has been consumed once.
 */
val InstallAuth = createRouteScopedPlugin("InstallAuth", ::InstallAuthConfig) {
    val store = requireNotNull(pluginConfig.store)
    val clock = pluginConfig.clock
    onCall { call ->
        if (call.isHandled) return@onCall
        val bearer = parseBearer(call.request.headers[HttpHeaders.Authorization])
        if (bearer == null) {
            call.deny(AuthStats.badBearer)
            return@onCall
        }
        val hash = hashSecret(bearer.secret)
        val row = try {
            store.authenticate(bearer.installId.toUuid(), hash)
        } catch (_: RevokedInstallException) {
            call.deny(AuthStats.revoked, "revoked")
            return@onCall
        }
        if (row == null) {
            call.deny(AuthStats.unknownInstall)
            return@onCall
        }
        val body = call.readLimitedBody() ?: return@onCall
        val timestamp = call.request.headers["X-Census-Timestamp"]
        if (timestamp == null || !RequestSigner.timestampInWindow(timestamp, clock.now())) {
            call.deny(AuthStats.skew)
            return@onCall
        }
        val canonical = RequestSigner.canonical(call.request.httpMethod.value, call.request.path(), timestamp, body)
        val signature = call.request.headers["X-Census-Signature"] ?: ""
        if (!RequestSigner.verify(bearer.secret, canonical, signature)) {
            call.deny(AuthStats.badSignature)
            return@onCall
        }
        val id = bearer.installId.value
        call.attributes.put(AuthenticatedInstallKey, AuthenticatedInstall(id, id.take(8), hash))
        call.attributes.put(AuthenticatedBodyKey, body)
        call.attributes.put(InstallRowKey, row)
        AuthStats.record(AuthStats.ok)
    }
}

private suspend fun ApplicationCall.deny(counter: AtomicLong, error: String = "unauthorized") {
    AuthStats.record(counter)
    respond(HttpStatusCode.Unauthorized, ErrorResponse(error))
}

/** Read at most cap + 1 bytes, even for chunked bodies with no Content-Length. */
internal suspend fun ApplicationCall.readLimitedBody(): ByteArray? {
    val channel = receiveChannel()
    val bytes = ByteArray(1_048_577)
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count == -1) break
        size += count
    }
    if (size > 1_048_576) {
        channel.cancel(null)
        respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large"))
        return null
    }
    return bytes.copyOf(size)
}
