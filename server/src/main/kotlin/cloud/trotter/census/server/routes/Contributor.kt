package cloud.trotter.census.server.routes

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.HashKeying
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.QuarantineNote
import cloud.trotter.census.server.SlotRole
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.auth.RequestInstantKey
import cloud.trotter.census.server.db.ContributorGate
import cloud.trotter.census.server.db.ContributorOutcome
import cloud.trotter.census.server.db.ContributorStore
import cloud.trotter.census.server.db.GrantOutcome
import cloud.trotter.census.server.db.PauseStore
import cloud.trotter.census.server.db.QuarantineResult
import cloud.trotter.census.server.db.QuarantineStore
import cloud.trotter.census.server.db.SlotScope
import cloud.trotter.census.server.db.SlotVariant
import cloud.trotter.census.server.db.WordItem
import cloud.trotter.census.server.db.WordStatus
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.ingest.hasDuplicateKeys
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.auth.sha256Hex
import cloud.trotter.census.server.ingest.storedKey
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

private val contributorJson = Json { encodeDefaults = true; explicitNulls = false }
private val slotPattern = Regex("^[A-Za-z0-9/]{1,512}$")
private val contributorLog = LoggerFactory.getLogger("Contributor")

/**
 * The contributor tier's install routes (#81, ADR-0011 §15), mounted inside identityRoutes' authenticated install rate
 * limit: the body was already read and its signature checked. Logs carry counts and outcomes only, never an install
 * key, a hash, a slot or a word.
 */
fun Route.contributorRoutes(
    contributors: ContributorStore, quarantine: QuarantineStore?, keying: HashKeying, clock: Clock, policy: Policy, pause: PauseStore,
) {
    post("/contributor/grant") {
        val request = decodeContributor<GrantRequest>(call.attributes[AuthenticatedBodyKey])
        when (val outcome = contributors.requestGrant(call.installId(), call.keyHash(), request.eligible, call.today(clock))) {
            is GrantOutcome.Granted -> call.respond(outcome.view.response())
            GrantOutcome.NotEligible -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_eligible"))
            GrantOutcome.TooNew -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("install_too_new"))
            GrantOutcome.Revoked -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("grant_revoked"))
            GrantOutcome.Stale -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        }
    }
    get("/contributor/grant") {
        val view = contributors.grantStatus(call.installId(), call.keyHash(), call.today(clock))
        if (view == null) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")) else call.respond(view.response())
    }
    post("/contributor/classes") {
        val request = decodeContributor<ClassesRequest>(call.attributes[AuthenticatedBodyKey])
        val scope = request.variant.scope(keying, policy) ?: badRequest()
        // Sol S3: the caller names the slots it asks about (its own skeleton's); ownership is checked on them first.
        if (request.slots.isEmpty() || request.slots.size > policy.slotConstancy.maxSlotsPerSkeleton ||
            request.slots.any { !slotPattern.matches(it) }) badRequest()
        call.answer(contributors.classes(call.installId(), call.keyHash(), call.today(clock), scope, request.slots.toSet())) { classes ->
            contributorLog.info("contributor classes answered={}", classes.size)
            ClassesResponse(call.today(clock).toString(), classes.map { (slot, slotClass) -> SlotClassDto(slot, slotClass.wire) })
        }
    }
    post("/contributor/gates") {
        val request = decodeContributor<GatesRequest>(call.attributes[AuthenticatedBodyKey])
        val scope = request.variant.scope(keying, policy) ?: badRequest()
        if (request.slots.isEmpty() || request.slots.size > policy.contributor.maxGateItems ||
            request.slots.any { !slotPattern.matches(it.slot) || !CensusHash.isWellFormed(it.hash) }) badRequest()
        val items = request.slots.map { it.slot to it.hash }
        call.answer(contributors.gates(call.installId(), call.keyHash(), call.today(clock), scope, items)) { open ->
            contributorLog.info("contributor gates asked={} open={}", open.size, open.count { it })
            GatesResponse(request.slots.zip(open).map { (item, isOpen) -> GateDto(item.slot, item.hash, isOpen) })
        }
    }
    post("/contributor/review") {
        if (call.refusedByPause(pause)) return@post
        val request = decodeContributor<ReviewRequest>(call.attributes[AuthenticatedBodyKey])
        val scope = request.variant.scope(keying, policy) ?: badRequest()
        val roles = request.roles.map { it.slot to (SlotRole.fromWire(it.role) ?: badRequest()) }
        if (request.confirmations.size + request.roles.size !in 1..policy.contributor.maxReviewItems ||
            request.confirmations.any { !slotPattern.matches(it.slot) || !CensusHash.isWellFormed(it.hash) } ||
            roles.any { !slotPattern.matches(it.first) }) badRequest()
        val confirmations = request.confirmations.map { it.slot to it.hash }
        call.answer(contributors.review(call.installId(), call.keyHash(), call.today(clock), scope, confirmations, roles)) { result ->
            contributorLog.info("contributor review confirmations={} roles={} skipped={}", result.confirmations, result.roles, result.skipped)
            ReviewResponse(result.confirmations, result.roles, result.skipped)
        }
    }
    post("/contributor/words") {
        if (call.refusedByPause(pause)) return@post
        val request = decodeContributor<WordsRequest>(call.attributes[AuthenticatedBodyKey])
        val scope = request.variant.scope(keying, policy) ?: badRequest()
        if (request.words.isEmpty() || request.words.size > policy.contributor.maxWordItems ||
            request.words.any { !slotPattern.matches(it.slot) || !CensusHash.isWellFormed(it.hash) || it.word.length > ContributorGate.MAX_WORD_LENGTH }) badRequest()
        val items = request.words.map { WordItem(it.slot, it.hash, it.word) }
        call.answer(contributors.words(call.installId(), call.keyHash(), call.today(clock), scope, items)) { statuses ->
            contributorLog.info("contributor words sent={} accepted={}", statuses.size, statuses.count { it == WordStatus.ACCEPTED })
            WordsResponse(request.words.zip(statuses).map { (item, status) -> WordResultDto(item.slot, status.wire) })
        }
    }
    post("/contributor/quarantine") {
        if (call.refusedByPause(pause)) return@post
        if (quarantine == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("quarantine_unconfigured"))
            return@post
        }
        val request = decodeContributor<QuarantineRequest>(call.attributes[AuthenticatedBodyKey])
        val note = request.note?.let { QuarantineNote.fromWire(it) ?: badRequest() }
        val bytes = try { Base64.getUrlDecoder().decode(request.artifact) } catch (_: IllegalArgumentException) { badRequest() }
        if (bytes.isEmpty() || bytes.size > policy.contributor.maxArtifactBytes) badRequest()
        val today = call.today(clock)
        val artifact = QuarantineArtifact.parse(bytes, keying, policy, today)
        if (artifact == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("bad_artifact"))
            return@post
        }
        // Sol S1: only the validated artifact, in its canonical form, is sealed; never the bytes as received.
        val result = quarantine.submit(call.installId(), call.keyHash(), today, artifact.canonical, note, artifact.words)
        call.answer(result) { stored ->
            when (stored) {
                is QuarantineResult.Stored -> {
                    contributorLog.info("contributor quarantine stored=1 bytes={}", artifact.canonical.size)
                    QuarantineResponse(expiresDay = stored.expiresDay.toString(), artifactSha256 = sha256Hex(artifact.canonical))
                }
                QuarantineResult.GateClosed -> {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("gate_closed"))
                    null
                }
            }
        }
    }
}

/**
 * The quarantine artifact (#81, gig.log#1627): the outbound review as the review screen described it, a JSON object
 * holding the skeleton (any accepted schema, validated like an upload, so it holds no screen text), and the review's
 * confirmations, role tags and words. A word in it must pass the same checks and find its gate open, or nothing is
 * stored: the quarantine is not a way around the gate.
 *
 * Sol S1: a JSON parser keeps one of two values under a repeated key, so validating the parsed object and sealing the
 * bytes as received could seal the other (ungated text). A repeated key at any depth is therefore refused, and what
 * is sealed is [canonical]: the validated object re-serialized, `{"skeleton":<the contract's canonical skeleton>,
 * "confirmations":[{"slot","hash"}],"roles":[{"slot","role"}],"words":[{"slot","hash","word"}]}`, keys in that order,
 * compact, every array present. A phone that sends this form gets back the SHA-256 of exactly its bytes.
 */
internal class QuarantineArtifact(val words: List<Triple<SlotScope, String, String>>, val canonical: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray, keying: HashKeying, policy: Policy, today: LocalDate): QuarantineArtifact? {
            val element = parseBounded(bytes) as? JsonObject ?: return null
            if (hasDuplicateKeys(bytes.decodeToString())) return null
            val body = try {
                contributorJson.decodeFromJsonElement(ArtifactDto.serializer(), element)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val accepted = SkeletonValidator.validate(body.skeleton, policy, today) as? ItemVerdict.Accepted ?: return null
            val item = accepted.item
            val fingerprint = keying.storedKey(item.kind.wire, item.fingerprint)
            val scope = SlotScope(fingerprint, SlotVariant(item.platformAppVersion ?: "unknown", item.hashDomain, item.filterRev, SlotWalk.layout(item)))
            if (body.confirmations.any { !slotPattern.matches(it.slot) || !CensusHash.isWellFormed(it.hash) } ||
                body.roles.any { !slotPattern.matches(it.slot) || SlotRole.fromWire(it.role) == null } ||
                body.words.size > policy.contributor.maxWordItems) return null
            val words = body.words.map { word ->
                if (!slotPattern.matches(word.slot) || !CensusHash.isWellFormed(word.hash)) return null
                if (ContributorGate.checkWord(word.word, word.hash).second != null) return null
                Triple(scope, word.slot, keying.token(word.hash))
            }
            val canonical = buildString {
                append("{\"skeleton\":").append(accepted.canonicalJson)
                append(",\"confirmations\":").append(contributorJson.encodeToString(ListSerializer(SlotHashDto.serializer()), body.confirmations))
                append(",\"roles\":").append(contributorJson.encodeToString(ListSerializer(RoleDto.serializer()), body.roles))
                append(",\"words\":").append(contributorJson.encodeToString(ListSerializer(WordDto.serializer()), body.words))
                append('}')
            }
            return QuarantineArtifact(words, canonical.toByteArray(Charsets.UTF_8))
        }
    }
}

private suspend inline fun <T, reified R : Any> ApplicationCall.answer(outcome: ContributorOutcome<T>, body: (T) -> R?) {
    when (outcome) {
        is ContributorOutcome.Ok -> body(outcome.value)?.let { respond(it) }
        ContributorOutcome.Stale -> respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        ContributorOutcome.NoGrant -> respond(HttpStatusCode.Forbidden, ErrorResponse("grant_required"))
        is ContributorOutcome.BudgetExhausted -> {
            response.headers.append(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
            respond(HttpStatusCode.TooManyRequests, ErrorResponse("budget_exhausted"))
        }
        ContributorOutcome.ReleaseUnavailable -> {
            response.headers.append(HttpHeaders.RetryAfter, RELEASE_RETRY_AFTER_SECONDS.toString())
            respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("release_unavailable"))
        }
    }
}

/** How long a phone waits before asking again for a release that is not built yet (the sweep runs every 6 hours). */
internal const val RELEASE_RETRY_AFTER_SECONDS: Long = 3600

private fun ApplicationCall.installId() = attributes[InstallRowKey].id
private fun ApplicationCall.keyHash() = attributes[AuthenticatedInstallKey].secretHash
private fun ApplicationCall.today(clock: Clock): LocalDate =
    (attributes.getOrNull(RequestInstantKey) ?: clock.now()).atOffset(ZoneOffset.UTC).toLocalDate()

private fun badRequest(): Nothing = throw BadRequestException("Invalid contributor request")

private inline fun <reified T> decodeContributor(bytes: ByteArray): T = try {
    val element = parseBounded(bytes) ?: throw BadRequestException("Invalid JSON")
    // Sol S1: a request with a repeated key means two things; refuse it rather than let the parser pick one.
    if (hasDuplicateKeys(bytes.decodeToString())) throw BadRequestException("Duplicate key")
    Json.decodeFromJsonElement<T>(element)
} catch (_: SerializationException) {
    throw BadRequestException("Invalid JSON")
} catch (_: IllegalArgumentException) {
    throw BadRequestException("Invalid JSON")
}

private fun cloud.trotter.census.server.db.GrantView.response() =
    GrantResponse(status, grantedDay?.toString(), expiresDay?.toString())

/** The cluster variant a contributor names (#81): the public fingerprint as the phone computed it, and the #80 variant. */
@Serializable
internal data class VariantDto(
    val kind: String,
    val fingerprint: String,
    val platformAppVersion: String,
    val hashDomain: Int,
    val filterRev: Int,
    val layout: String,
) {
    /** The stored scope, or null when any field is malformed or outside the policy. */
    fun scope(keying: HashKeying, policy: Policy): SlotScope? {
        val skeletonKind = SkeletonKind.fromWire(kind) ?: return null
        if (!CensusFingerprint.isWellFormed(fingerprint) || !CensusFingerprint.isWellFormed(layout)) return null
        if (platformAppVersion != "unknown" && !WireGrammars.platformAppVersion.matches(platformAppVersion)) return null
        if (hashDomain !in policy.acceptedHashDomains || filterRev < policy.minimumFilterRev) return null
        return SlotScope(keying.storedKey(skeletonKind.wire, fingerprint), SlotVariant(platformAppVersion, hashDomain, filterRev, layout))
    }
}

@Serializable private data class GrantRequest(val eligible: Boolean)
@Serializable private data class GrantResponse(val status: String, val grantedDay: String? = null, val expiresDay: String? = null)
@Serializable private data class ClassesRequest(val variant: VariantDto, val slots: List<String>)
@Serializable private data class SlotClassDto(val slot: String, @SerialName("class") val slotClass: String)
@Serializable private data class ClassesResponse(val releaseDay: String, val slots: List<SlotClassDto>)
@Serializable internal data class SlotHashDto(val slot: String, val hash: String)
@Serializable private data class GatesRequest(val variant: VariantDto, val slots: List<SlotHashDto>)
@Serializable private data class GateDto(val slot: String, val hash: String, val open: Boolean)
@Serializable private data class GatesResponse(val slots: List<GateDto>)
@Serializable internal data class RoleDto(val slot: String, val role: String)
@Serializable private data class ReviewRequest(
    val variant: VariantDto, val confirmations: List<SlotHashDto> = emptyList(), val roles: List<RoleDto> = emptyList(),
)
@Serializable private data class ReviewResponse(val confirmations: Int, val roles: Int, val skipped: Int)
@Serializable internal data class WordDto(val slot: String, val hash: String, val word: String)
@Serializable private data class WordsRequest(val variant: VariantDto, val words: List<WordDto>)
@Serializable private data class WordResultDto(val slot: String, val status: String)
@Serializable private data class WordsResponse(val words: List<WordResultDto>)
@Serializable private data class QuarantineRequest(val artifact: String, val note: String? = null)
@Serializable private data class QuarantineResponse(val status: String = "quarantined", val expiresDay: String, val artifactSha256: String)
@Serializable private data class ArtifactDto(
    val skeleton: JsonElement,
    val confirmations: List<SlotHashDto> = emptyList(),
    val roles: List<RoleDto> = emptyList(),
    val words: List<WordDto> = emptyList(),
)
