package cloud.trotter.census.server.routes

import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.contract.authoring.DraftResult
import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary
import cloud.trotter.census.contract.authoring.RuleDraft
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.db.PinnedEnvelope
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.ops.ClusterDraftHtml
import cloud.trotter.census.server.ops.ClusterFilter
import cloud.trotter.census.server.ops.ClustersPageHtml
import cloud.trotter.census.server.ops.DashboardHtml
import cloud.trotter.census.server.ops.DraftDecode
import cloud.trotter.census.server.ops.DraftForm
import cloud.trotter.census.server.ops.DraftNode
import cloud.trotter.census.server.ops.OpsForm
import cloud.trotter.census.server.ops.RenderedWireframe
import cloud.trotter.census.server.ops.WireframeRender
import cloud.trotter.census.server.ops.fingerprintPattern
import cloud.trotter.census.server.ops.platform
import cloud.trotter.census.server.ops.safe
import cloud.trotter.census.server.ops.version
import cloud.trotter.census.server.today
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject

/** Mounted only inside the operator authentication gate; mutations also require the shared second factor. */
fun Route.dashboardRoute(store: OpsStore, alarms: HealthAlarms, clock: Clock, policy: Policy) {
    get("/") {
        val html = DashboardHtml.render(
            policy.serverVersion, policy.k, clock.today().toString(), opsAlarms(alarms),
            store.clusterSummary(), store.health(), store.installs(), store.ledger(), store.vocabularyQueueCount(), store.vocabularyQueueDisplay(),
        )
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html)
    }
    get("/clusters/view") {
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        // A malformed percent-escape never reaches here: routing decodes the query first and the module's
        // bad-request handler answers the HTML shell for ops pages.
        val parameters = call.request.queryParameters
        val filter = ClusterFilter.parse(parameters["platform"], parameters["version"], parameters["status"], parameters["page"], parameters["kind"])
        val today = clock.today().toString()
        if (filter == null) {
            call.respondText(ClustersPageHtml.renderInvalid(policy.serverVersion, policy.k, today), ContentType.Text.Html, HttpStatusCode.BadRequest)
        } else {
            val result = store.clustersPage(filter.platform, filter.version, filter.status, filter.page, kind = filter.kind)
            call.respondText(ClustersPageHtml.render(policy.serverVersion, policy.k, today, result), ContentType.Text.Html)
        }
    }
    get("/clusters/{fingerprint}/view") {
        val fingerprint = call.parameters["fingerprint"]
        val cluster = if (fingerprint != null && fingerprintPattern.matches(fingerprint)) store.cluster(fingerprint, withWireframe = true) else null
        val html = ClusterDetailHtml.render(policy.serverVersion, policy.k, clock.today().toString(), cluster)
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html, if (cluster == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
    }
    get("/clusters/{fingerprint}/draft") {
        val fp = call.draftFingerprint()
        val cluster = store.cluster(fp)
        if (cluster == null) { call.draftMissing(policy, clock); return@get }
        val capture = store.pinnedEnvelope(fp)
        val context = capture?.let { draftContext(it) }
        call.draftPage(policy, clock, cluster, context)
    }
    for (operation in listOf("shape", "preview", "save")) {
        val path = if (operation == "save") "/clusters/{fingerprint}/draft" else "/clusters/{fingerprint}/draft/$operation"
        post(path) {
            val fp = call.draftFingerprint()
            val cluster = store.cluster(fp)
            if (cluster == null) { call.draftMissing(policy, clock); return@post }
            val params = call.attributes.getOrNull(OpsForm) ?: throw BadRequestException("Expected operator form")
            if (operation == "save" && params["mode"] == "classify") {
                val screenClass = params["screenClass"]?.takeUnless { it == "unknown" }
                val notes = if (cluster.notesWithheld) "" else params["notes"].orEmpty()
                if ((screenClass != null && screenClass !in RuleAuthoringVocabulary.SCREEN_CLASSES) || notes.length > 2000) {
                    call.draftPage(policy, clock, cluster, null, params, listOf("Invalid classification or notes"), status = HttpStatusCode.BadRequest)
                } else if (store.saveClassification(fp, screenClass, notes.ifEmpty { null })) call.draftRedirect(fp)
                else call.draftMissing(policy, clock)
                return@post
            }
            if (cluster.kind == SkeletonKind.NOTIFICATION) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("unsupported_skeleton_kind"))
                return@post
            }
            val id = params["envelopeId"]?.toLongOrNull()?.takeIf { it > 0 }
            val capture = id?.let { store.pinnedEnvelope(fp, it) }
            val context = capture?.let { draftContext(it) }
            if (context == null) {
                call.draftPage(policy, clock, cluster, null, params, errors = listOf("This capture changed — reload"),
                    nested = operation != "save", status = HttpStatusCode.Conflict)
                return@post
            }
            if (operation == "shape") {
                // A shape refresh allows incomplete intent/class and previous-shape fields, but retains all bounds.
                val refreshed = DraftForm(context.rows.take(DraftForm.MAX_ROWS)).decode(params, shapeRefresh = true)
                call.draftPage(policy, clock, cluster, context, params,
                    errors = (refreshed as? DraftDecode.Errors)?.errors.orEmpty(), nested = true,
                    status = if (refreshed is DraftDecode.Ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
                return@post
            }
            val decoded = DraftForm(context.rows.take(DraftForm.MAX_ROWS)).decode(params)
            if (decoded is DraftDecode.Errors) {
                call.draftPage(policy, clock, cluster, context, params, decoded.errors,
                    nested = operation != "save", status = HttpStatusCode.BadRequest)
                return@post
            }
            val decodedInput = (decoded as DraftDecode.Ok).value
            val input = decodedInput.copy(notes = decodedInput.notes?.ifEmpty { null }?.takeUnless { cluster.notesWithheld })
            val pinned = context.capture
            val generated = RuleDraft.generate(context.envelope, input.selections, platform(cluster.platform),
                pinned.platformAppVersion?.let { version(it) }, pinned.receivedDay)
            // Refuse identifier-bearing output rather than changing bytes between preview, storage and download.
            val result = if (generated is DraftResult.Ok && safe(generated.json5) != generated.json5) {
                DraftResult.Refused(listOf("Draft contains an identifier; remove it from the selections or use another capture"))
            } else generated
            when (result) {
                is DraftResult.Refused -> call.draftPage(policy, clock, cluster, context, params, result.errors,
                    nested = operation != "save", status = if (operation == "save") HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK)
                is DraftResult.Ok -> if (operation == "preview") {
                    call.draftPage(policy, clock, cluster, context, params, json5 = result.json5, warnings = result.warnings, nested = true)
                } else {
                    if (store.saveDraft(fp, input.selections.screenClass, DraftForm.selectionsJson(input), result.json5, clock.today())) {
                        call.draftRedirect(fp)
                    } else call.draftPage(policy, clock, cluster, null, params, errors = listOf("This capture changed — reload"), status = HttpStatusCode.Conflict)
                }
            }
        }
    }
    get("/clusters/{fingerprint}/draft.json5") {
        // An operator-saved artefact survives revocation; withdrawal and retention delete it transactionally.
        val draft = store.draftJson5(call.draftFingerprint())
        if (draft == null) call.respond(HttpStatusCode.NotFound)
        else {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"draft.json5\"")
            call.respondText(draft, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }
    }

}

private data class DraftContext(val capture: PinnedEnvelope, val envelope: JsonObject, val frame: RenderedWireframe, val rows: List<DraftNode>)

private fun draftContext(capture: PinnedEnvelope): DraftContext? {
    val envelope = parseBounded(capture.bytes.toByteArray(), maxDepth = 144) as? JsonObject ?: return null
    val payload = envelope["payload"] as? JsonObject ?: return null
    val walked = EnvelopeWalk.walk(payload)
    val frame = WireframeRender.render(envelope, walked, capture.receivedDay, capture.installPrefix) ?: return null
    return DraftContext(capture, envelope, frame, DraftForm.rows(walked, frame))
}

private fun ApplicationCall.draftFingerprint(): String = parameters["fingerprint"]?.takeIf { fingerprintPattern.matches(it) }
    ?: throw BadRequestException("Invalid cluster")

private suspend fun ApplicationCall.draftRedirect(fp: String) {
    response.headers.append(HttpHeaders.Location, "/ops/clusters/$fp/view")
    respond(HttpStatusCode.SeeOther)
}

private suspend fun ApplicationCall.draftMissing(policy: Policy, clock: Clock) {
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
    respondText(ClusterDetailHtml.render(policy.serverVersion, policy.k, clock.today().toString(), null), ContentType.Text.Html, HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.draftPage(
    policy: Policy, clock: Clock, cluster: OpsCluster, context: DraftContext?, state: Parameters = Parameters.Empty,
    errors: List<String> = emptyList(), json5: String? = null, warnings: List<String> = emptyList(),
    nested: Boolean = false, status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
    respondText(ClusterDraftHtml.render(policy.serverVersion, policy.k, clock.today().toString(), cluster,
        context?.capture, context?.frame, context?.rows.orEmpty(), state, errors, json5, warnings, nested), ContentType.Text.Html, status)
}
