package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.SkeletonKind

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
        val screen = cluster.kind == SkeletonKind.SCREEN
        val eligibleCapture = capture?.takeIf { screen }
        section("panel") {
            h1 { +if (screen) "Classify & draft" else "Classify notification" }
            kindChip(cluster.kind)
            statusChip(cluster.status)
            classChip(cluster.screenClass)
            clusterFacts(cluster, detail = true)
            if (screen) p { +"Drafts quote trusted-capture text and are operator-only. The app test suite is the gate; the server only drafts." }
            if (screen) p { +"Sensitive screens usually extend the known rule in sensitive.json5. Choose a free priority." }
        }
        if (screen && frame != null) panel("wireframe", "Screen wireframe") {
            val renderedRows = if (eligibleCapture != null) rows.take(DraftForm.MAX_ROWS).map { it.number }.toSet() else emptySet()
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
                        if (index + 1 in renderedRows) a(href = "#node-${index + 1}", classes = "wire-n") { +"${index + 1}" }
                        else span("wire-n") { +"${index + 1}" }
                        box.label?.let { span("wire-label") { +safe(it) } }
                    }
                }
            }
        }
        section("panel") {
            val screenClass = state["screenClass"] ?: cluster.screenClass ?: "unknown"
            val shapes = V.LEGAL_SHAPES_BY_CLASS[screenClass] ?: V.LEGAL_SHAPES_BY_CLASS.values.flatten().distinct()
            val shape = state["shape"]?.takeIf { it in shapes } ?: V.DEFAULT_SHAPE_BY_CLASS[screenClass] ?: "none"
            val fields = V.FIELDS_BY_SHAPE[shape].orEmpty().map { it.name }
            form(action = if (nested) "../draft" else "draft", method = FormMethod.post) {
                if (errors.isNotEmpty()) {
                    h2 { +"Draft errors" }
                    ul("errors") {
                        attributes["role"] = "alert"
                        errors.forEach { li { +safe(it) } }
                        // A class can be recorded without drafting a rule; a blank intent is only a drafting problem.
                        if (eligibleCapture != null && errors.any { it.contains("intent", ignoreCase = true) }) {
                            li { +"To record the screen class without drafting a rule, use \"Save classification only\" (no intent needed)." }
                        }
                    }
                }
                // Browser validation must not block the pure shape refresh or a preview of an incomplete draft.
                if (eligibleCapture == null) {
                    input(type = InputType.hidden, name = "mode") { value = "classify" }
                    p { +if (screen) "A trusted capture is needed to draft. You can still classify this cluster and save notes."
                        else "Notification rule drafting and trusted notification captures are deferred. Classification and notes are available." }
                } else {
                    input(type = InputType.hidden, name = "envelopeId") { value = eligibleCapture.id.toString() }
                }
                fieldSet {
                    legend { +"Classification" }
                    choice("screenClass", if (screen) "Screen class" else "Classification", listOf("unknown") + V.SCREEN_CLASSES, screenClass)
                    if (eligibleCapture != null) {
                        choice("shape", "Shape", shapes, shape)
                        textControl("intent", "Intent", state["intent"].orEmpty(), 48) { pattern = V.INTENT.pattern }
                        textControl("priority", "Priority", state["priority"] ?: "500", 3, InputType.number) { min = "1"; max = "998" }
                        choice("modeHint", "Mode hint", listOf("") + V.MODES, state["modeHint"].orEmpty())
                        choice("offerSurface", "Offer surface", listOf("") + V.OFFER_SURFACES, state["offerSurface"].orEmpty())
                        label { +"Comment"; textArea { name = "comment"; maxLength = "500"; +safe(state["comment"].orEmpty()) } }
                    }
                    if (cluster.notesWithheld) p { +"Notes withheld below the privacy gate" }
                    else label { +"Notes"; textArea { name = "notes"; maxLength = "2000"; +safe(state["notes"] ?: cluster.notes.orEmpty()) } }
                }
                if (eligibleCapture != null) {
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
                                    th { attributes["scope"] = "row"; id = "node-$n"; +"$n" }
                                    td { +safe(node.text ?: node.desc ?: node.hint ?: node.pane ?: "(no label)") }
                                    td { +safe(node.simpleClass ?: "view") }
                                    td { +safe(node.idSuffix ?: node.viewId ?: "no id") }
                                    td { +listOfNotNull(if (node.takesClick) "clickable" else null, if (!node.visible) "hidden" else null).joinToString(" / ") }
                                    td {
                                        choice("role_$n", "Role", listOf("", "anchor", "field", "bind", "redact"), state["role_$n"].orEmpty(), node = n)
                                        choice("field_$n", "Field", listOf("") + fields, state["field_$n"].orEmpty(), node = n)
                                        choice("field2_$n", "Second field", listOf("") + fields, state["field2_$n"].orEmpty(), node = n)
                                        choice("transform_$n", "Transform", listOf("default", "none") + V.TRANSFORMS, state["transform_$n"] ?: "default", node = n)
                                        textControl("stripPrefix_$n", "Strip prefix", state["stripPrefix_$n"].orEmpty(), 40, node = n)
                                        choice("bind_$n", "Bind", listOf("") + V.BIND_TARGETS.keys, state["bind_$n"].orEmpty(), node = n)
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
                if (screen && eligibleCapture == null && state["envelopeId"] != null) {
                    // Preserve typed entries without exposing an ineligible capture or repinning its node numbers.
                    fieldSet {
                        legend { +"Unsaved draft entries" }
                        p { +"Copy these entries before reloading; node numbers must be checked against the new capture." }
                        state.entries().forEach { (key, values) ->
                            recoveryTitle(key)?.let { title ->
                                label { +title; textArea { name = key; attributes["readonly"] = "readonly"; +safe(values.single()) } }
                            }
                        }
                    }
                }
                label {
                    +"TOTP for Save"
                    input(type = InputType.text, name = "totp") {
                        pattern = "[0-9]{6}"; maxLength = "6"
                        attributes["inputmode"] = "numeric"; attributes["autocomplete"] = "one-time-code"
                    }
                }
                if (eligibleCapture == null) {
                    button(type = ButtonType.submit) { +"Save classification" }
                } else {
                    // Classification is operator knowledge independent of any rule: saving it must not require a draft.
                    button(type = ButtonType.submit) {
                        name = "mode"; value = "classify"
                        attributes["formnovalidate"] = "formnovalidate"
                        +"Save classification only"
                    }
                    button(type = ButtonType.submit) { +"Save draft" }
                }
            }
        }
        if (screen && json5 != null) section("panel") {
            h2 { +"Draft preview" }
            pre("draft") { +safe(json5) }
        }
        if (warnings.isNotEmpty()) section("panel") {
            h2 { +"Warnings" }
            ul { warnings.forEach { li { +safe(it) } } }
        }
    }

    private val RECOVERY_FIELDS = mapOf("shape" to "Shape", "intent" to "Intent", "priority" to "Priority",
        "modeHint" to "Mode hint", "offerSurface" to "Offer surface", "comment" to "Comment") +
        (1..4).flatMap { listOf("constName_$it" to "Constant $it name", "constValue_$it" to "Constant $it value") }
    private val RECOVERY_ROW = Regex("(role|field|field2|transform|stripPrefix|bind)_([1-9][0-9]{0,3})")
    private val ROW_TITLES = mapOf("role" to "Role", "field" to "Field", "field2" to "Second field",
        "transform" to "Transform", "stripPrefix" to "Strip prefix", "bind" to "Bind")

    private fun recoveryTitle(key: String): String? = RECOVERY_FIELDS[key] ?: RECOVERY_ROW.matchEntire(key)?.let {
        "${ROW_TITLES.getValue(it.groupValues[1])} for node ${it.groupValues[2]}"
    }

    private fun FlowContent.choice(key: String, title: String, choices: List<String>, current: String, node: Int? = null) {
        label {
            +title
            select { name = key
                if (node != null) {
                    attributes["aria-label"] = "$title for node $node"
                    attributes["aria-describedby"] = "node-$node"
                }
                // Keep a previous shape's choices visible until the operator changes them; decode still validates.
                (choices + listOf(current)).distinct().forEach { item -> option {
                    value = safeAttribute(item); selected = item == current; +safe(item.ifEmpty { "—" })
                } }
            }
        }
    }

    private fun FlowContent.textControl(key: String, title: String, current: String, limit: Int,
        type: InputType = InputType.text, node: Int? = null, extra: INPUT.() -> Unit = {}) {
        label { +title; input(type = type, name = key) {
            value = safeAttribute(current); maxLength = limit.toString()
            if (node != null) {
                attributes["aria-label"] = "$title for node $node"
                attributes["aria-describedby"] = "node-$node"
            }
            extra()
        } }
    }
}
