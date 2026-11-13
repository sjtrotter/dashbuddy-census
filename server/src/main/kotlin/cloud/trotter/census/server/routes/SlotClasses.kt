package cloud.trotter.census.server.routes

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.db.SlotStore
import cloud.trotter.census.server.db.SlotVariant
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.ingest.parseBounded
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val slotJson = Json { explicitNulls = false }
private val layoutPattern = Regex("[0-9a-f]{64}")

/** At most this many clusters per request: the review screen shows at most 3 screens a day (ADR-0011 §15). */
internal const val MAX_SLOT_CLASS_ITEMS: Int = 8

/**
 * The contributor review screen's read (#80, app #1626): per cluster variant and raw layout, each slot's class from
 * the daily release with the asking install's own rows subtracted ([SlotStore]), never another install's hash and
 * never a count. Slots below k are left out, so a cluster fewer than k installs saw answers exactly as one nobody saw. Mounted inside identityRoutes' authenticated install rate limit; the body is signed, so the
 * fingerprints asked about never sit in a path or a log line. Never paused: it writes nothing.
 */
fun Route.slotClassRoutes(slots: SlotStore, policy: Policy) {
    post("/slot-classes") {
        val request = try {
            val bytes = call.attributes[AuthenticatedBodyKey]
            if (parseBounded(bytes) == null) null else slotJson.decodeFromString<SlotClassRequest>(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: SerializationException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
        val items = request?.items
        if (items == null || items.isEmpty() || items.size > MAX_SLOT_CLASS_ITEMS || items.any { !it.wellFormed() }) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }
        val installId = call.attributes[InstallRowKey].id
        call.respond(SlotClassResponse(items.map { item ->
            val kind = requireNotNull(SkeletonKind.fromWire(item.kind))
            val variant = SlotVariant(item.platformAppVersion ?: "unknown", item.hashDomain, item.filterRev, item.layout)
            // A partition this server does not count in has no classes; it answers like an unseen cluster.
            val counted = item.hashDomain in policy.acceptedHashDomains && item.filterRev >= policy.minimumFilterRev
            SlotClassItem(item.fingerprint, if (counted) slots.appClasses(installId, kind, item.fingerprint, variant) else emptyMap())
        }))
    }
}

/** One cluster variant, named as the phone's own skeleton names it (its public fingerprint and identity fields). */
@Serializable
internal data class SlotClassQuery(
    val kind: String,
    val fingerprint: String,
    val platformAppVersion: String? = null,
    val hashDomain: Int,
    val filterRev: Int,
    /** The raw layout id the phone computed from the same skeleton ([cloud.trotter.census.server.ingest.SlotWalk.layout]). */
    val layout: String,
) {
    fun wellFormed(): Boolean = SkeletonKind.fromWire(kind) != null && CensusFingerprint.isWellFormed(fingerprint) &&
        layoutPattern.matches(layout) &&
        (platformAppVersion == null || WireGrammars.platformAppVersion.matches(platformAppVersion)) &&
        hashDomain > 0 && filterRev > 0
}

@Serializable
internal data class SlotClassRequest(val items: List<SlotClassQuery>)

@Serializable
internal data class SlotClassItem(val fingerprint: String, val slots: Map<String, SlotClass>)

@Serializable
internal data class SlotClassResponse(val items: List<SlotClassItem>)
