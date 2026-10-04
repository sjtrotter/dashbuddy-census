package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as V
import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.PinnedEnvelope
import io.ktor.http.Parameters
import kotlinx.html.*
import java.util.Locale

/** Only safe display strings reach HTML. The private envelope stays outside the DTO and template. */
object ClusterDraftHtml {
    fun render(
        serverVersion: String, k: Int, today: String, cluster: OpsCluster,
        capture: PinnedEnvelope?, frame: RenderedWireframe?, rows: List<DraftNode>,
        state: Parameters = Parameters.Empty, errors: List<String> = emptyList(),
        json5: String? = null, warnings: List<String> = emptyList(), nested: Boolean = false,
    ): String = opsPage(pageTitle = "Classify & draft · Census operator", headerContent = {
        metadata(serverVersion, k, today)
        if (fingerprintPattern.matches(cluster.fingerprint)) {
            a(href = "/ops/clusters/${cluster.fingerprint}/view", classes = "action") { +"Back to cluster" }
        }
    }) {
        section("panel") {
            h1 { +"Classify & draft" }
            statusChip(cluster.status)
            cluster.screenClass?.let { chip(safe(it), "neutral") }
            clusterFacts(cluster, detail = true)
            p { +"Drafts quote trusted-capture text and are operator-only. The app test suite is the gate; the server only drafts." }
            p { +"Sensitive screens usually extend the known rule in sensitive.json5. Choose a free priority." }
        }
        if (errors.isNotEmpty()) section("panel") {
            h2 { +"Draft errors" }
            ul("errors") { errors.forEach { li { +safe(it) } } }
        }
        if (frame != null) panel("wireframe", "Screen wireframe") {
            p("muted") {
                +"Trusted capture · received "; date(frame.receivedDay)
                +" · install ${prefix(frame.installPrefix)} · app version ${frame.platformAppVersion?.let { version(it) } ?: "Not recorded"}"
            }
            div("wire-frame") {
                attributes["style"] = "aspect-ratio:${frame.frameWidth}/${frame.frameHeight}"
                frame.boxes.forEachIndexed { index, box ->
                    div("wire-box" + (if (box.label != null) " labelled" else "") + (if (box.clickable) " clickable" else "")) {
                        attributes["style"] = String.format(Locale.ROOT, "left:%.2f%%;top:%.2f%%;width:%.2f%%;height:%.2f%%",
                            box.leftPct, box.topPct, box.widthPct, box.heightPct)
                        span("wire-n") { +"${index + 1}" }
                        box.label?.let { span("wire-label") { +safe(it) } }
                    }
                }
            }
        }
        section("panel") {
            val screenClass = state["screenClass"] ?: cluster.screenClass ?: "unknown"
            val shape = state["shape"] ?: V.DEFAULT_SHAPE_BY_CLASS[screenClass] ?: "none"
            val fields = V.FIELDS_BY_SHAPE[shape].orEmpty().map { it.name }
            form(action = if (nested) "../draft" else "draft", method = FormMethod.post) {
                // Browser validation must not block the pure shape refresh or a preview of an incomplete draft.
                if (capture == null) {
                    input(type = InputType.hidden, name = "mode") { value = "classify" }
                    p { +"A trusted capture is needed to draft. You can still classify this cluster and save notes." }
                } else {
                    input(type = InputType.hidden, name = "envelopeId") { value = capture.id.toString() }
                    input(type = InputType.hidden, name = "envelopePin") { value = capture.pin }
                }
                fieldSet {
                    legend { +"Classification" }
                    choice("screenClass", "Screen class", listOf("unknown") + V.SCREEN_CLASSES, screenClass)
                    if (capture != null) {
                        choice("shape", "Shape", V.SHAPES, shape)
                        textControl("intent", "Intent", state["intent"].orEmpty(), 48) { pattern = V.INTENT.pattern }
                        textControl("priority", "Priority", state["priority"] ?: "500", 3, InputType.number) { min = "1"; max = "998" }
                        choice("modeHint", "Mode hint", listOf("") + V.MODES, state["modeHint"].orEmpty())
                        choice("offerSurface", "Offer surface", listOf("") + V.OFFER_SURFACES, state["offerSurface"].orEmpty())
                        label { +"Comment"; textArea { name = "comment"; maxLength = "500"; +safe(state["comment"].orEmpty()) } }
                    }
                    label { +"Notes"; textArea { name = "notes"; maxLength = "2000"; +safe(state["notes"] ?: cluster.notes.orEmpty()) } }
                }
                if (capture != null) {
                    fieldSet {
                        legend { +"Constants" }
                        p { +"Allowed names: "; +DraftForm.constantFields(shape).joinToString(", ") { it.name }.ifEmpty { "None" } }
                        for (i in 1..4) {
                            textControl("constName_$i", "Constant $i name", state["constName_$i"].orEmpty(), 48)
                            textControl("constValue_$i", "Constant $i value", state["constValue_$i"].orEmpty(), 2000)
                        }
                    }
                    div("req") {
                        h2 { +"Required fields" }
                        val declared = rows.take(DraftForm.MAX_ROWS).flatMap { (n, _) ->
                            if (state["role_$n"] == "field") listOfNotNull(state["field_$n"], state["field2_$n"]) else emptyList()
                        } + (1..4).mapNotNull { state["constName_$it"] }
                        val required = V.REQUIRED_FIELDS_BY_SHAPE[shape].orEmpty().map { listOf(it) } + V.REQUIRED_ONE_OF_BY_SHAPE[shape].orEmpty()
                        if (required.isEmpty()) p { +"No required fields for this shape. An anchor is always required." }
                        required.forEach { group ->
                            val present = group.any { it in declared }
                            chip("${if (present) "✓" else "✗"} ${group.joinToString(" or ")}", if (present) "good" else "warn")
                        }
                    }
                    div("tree-wrap") {
                        table("nodes") {
                            caption { +"Nodes · numbers match the wireframe badges" }
                            thead { tr { listOf("n", "Label", "Class", "ID", "Flags", "Assignments").forEach { th { +it } } } }
                            tbody {
                                rows.take(DraftForm.MAX_ROWS).forEach { (n, node) -> tr {
                                    td { +"$n" }
                                    td { +safe(node.text ?: node.desc ?: node.hint ?: node.pane ?: "(no label)") }
                                    td { +safe(node.simpleClass ?: "view") }
                                    td { +safe(node.idSuffix ?: node.viewId ?: "no id") }
                                    td { +listOfNotNull(if (node.clickable) "clickable" else null, if (!node.visible) "hidden" else null).joinToString(" / ") }
                                    td {
                                        choice("role_$n", "Role", listOf("", "anchor", "field", "bind", "redact"), state["role_$n"].orEmpty())
                                        choice("field_$n", "Field", listOf("") + fields, state["field_$n"].orEmpty())
                                        choice("field2_$n", "Second field", listOf("") + fields, state["field2_$n"].orEmpty())
                                        choice("transform_$n", "Transform", listOf("default", "none") + V.TRANSFORMS, state["transform_$n"] ?: "default")
                                        textControl("stripPrefix_$n", "Strip prefix", state["stripPrefix_$n"].orEmpty(), 40)
                                        choice("bind_$n", "Bind", listOf("") + V.BIND_TARGETS.keys, state["bind_$n"].orEmpty())
                                    }
                                } }
                            }
                        }
                    }
                    if (rows.size > DraftForm.MAX_ROWS) p { +"${rows.size - DraftForm.MAX_ROWS} more nodes not shown" }
                    button(type = ButtonType.submit) {
                        attributes["formaction"] = if (nested) "shape" else "draft/shape"
                        attributes["formnovalidate"] = "formnovalidate"
                        +"Apply shape"
                    }
                    button(type = ButtonType.submit) {
                        attributes["formaction"] = if (nested) "preview" else "draft/preview"
                        attributes["formnovalidate"] = "formnovalidate"
                        +"Preview draft"
                    }
                }
                label {
                    +"TOTP for Save"
                    input(type = InputType.text, name = "totp") {
                        pattern = "[0-9]{6}"; maxLength = "6"
                        attributes["inputmode"] = "numeric"; attributes["autocomplete"] = "one-time-code"
                    }
                }
                button(type = ButtonType.submit) { +"Save" }
            }
        }
        if (json5 != null) section("panel") {
            h2 { +"Draft preview" }
            pre("draft") { +safe(json5) }
        }
        if (warnings.isNotEmpty()) section("panel") {
            h2 { +"Warnings" }
            ul { warnings.forEach { li { +safe(it) } } }
        }
    }

    private fun FlowContent.choice(key: String, title: String, choices: List<String>, current: String) {
        label {
            +title
            select { name = key
                // Keep a previous shape's choices visible until the operator changes them; decode still validates.
                (choices + listOf(current)).distinct().forEach { item -> option {
                    value = safe(item); selected = item == current; +safe(item.ifEmpty { "—" })
                } }
            }
        }
    }

    private fun FlowContent.textControl(key: String, title: String, current: String, limit: Int,
        type: InputType = InputType.text, extra: INPUT.() -> Unit = {}) {
        label { +title; input(type = type, name = key) { value = safe(current); maxLength = limit.toString(); extra() } }
    }
}
