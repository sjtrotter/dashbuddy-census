package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.authoring.*
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as V
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import kotlinx.serialization.json.*

/** A badge is presentation only; a node's original child-index path is its identity. */
data class DraftNode(val number: Int, val node: WalkedNode)
data class DraftInput(val selections: Selections, val envelopeId: Long, val envelopePin: String, val notes: String)
sealed interface DraftDecode {
    data class Ok(val value: DraftInput) : DraftDecode
    data class Errors(val errors: List<String>) : DraftDecode
}

class DraftForm(val nodes: List<DraftNode>) {
    init { require(nodes.size <= MAX_ROWS && nodes.map { it.number }.distinct().size == nodes.size) }

    fun decode(params: Parameters, shapeRefresh: Boolean = false): DraftDecode {
        val errors = mutableListOf<String>()
        if (params.entries().any { it.value.size != 1 }) errors += "Duplicate form key"
        fun text(key: String, limit: Int): String {
            val value = params[key].orEmpty()
            if (value.length > limit) errors += "$key is too long"
            return value
        }
        fun choice(key: String, choices: Collection<String>, optional: Boolean = false): String {
            val value = params[key].orEmpty()
            if (!(optional && value.isEmpty()) && value !in choices) errors += "Invalid $key"
            return value
        }
        val screenClass = choice("screenClass", V.SCREEN_CLASSES + if (shapeRefresh) listOf("unknown") else emptyList())
        val shape = choice("shape", V.SHAPES)
        val intent = text("intent", 48)
        if (!(shapeRefresh && intent.isEmpty()) && !V.INTENT.matches(intent)) errors += "Invalid intent"
        val priority = params["priority"]?.toIntOrNull()
        if (priority == null || priority !in V.PRIORITY_RANGE) errors += "Priority must be in 1–998"
        val mode = choice("modeHint", V.MODES, true).ifEmpty { null }
        val surface = choice("offerSurface", V.OFFER_SURFACES, true).ifEmpty { null }
        val comment = text("comment", 500).ifEmpty { null }
        val notes = text("notes", 2000)
        val envelopeId = params["envelopeId"]?.takeIf { Regex("[1-9][0-9]{0,18}").matches(it) }?.toLongOrNull()
        if (envelopeId == null) errors += "Invalid capture reference"
        val pin = params["envelopePin"].orEmpty()
        if (!PIN.matches(pin)) errors += "Invalid capture pin"
        val specs = if (shapeRefresh) V.FIELDS_BY_SHAPE.values.flatten() else V.FIELDS_BY_SHAPE[shape].orEmpty()
        val anchors = mutableListOf<NodeRef>()
        val fields = mutableListOf<FieldAssignment>()
        val binds = mutableListOf<BindAssignment>()
        val redacts = mutableListOf<NodeRef>()
        // Recognized node controls outside the displayed mapping are rejected, never reinterpreted.
        val shown = nodes.map { it.number }.toSet()
        params.names().forEach { key ->
            val match = ROW_KEY.matchEntire(key)
            if (match != null && match.groupValues[1].toIntOrNull() !in shown) errors += "Node is not shown"
        }
        for ((n, node) in nodes) {
            val role = choice("role_$n", listOf("anchor", "field", "bind", "redact"), true)
            val field = choice("field_$n", specs.map { it.name }, true)
            val field2 = choice("field2_$n", specs.map { it.name }, true)
            val transform = choice("transform_$n", listOf("default", "none") + V.TRANSFORMS, true)
            val bind = choice("bind_$n", V.BIND_TARGETS.keys, true)
            val stripPrefix = text("stripPrefix_$n", 40).ifEmpty { null }
            val ref = NodeRef(node.path)
            when (role) {
                "anchor" -> anchors += ref
                "redact" -> redacts += ref
                "bind" -> if (bind.isEmpty()) errors += "Choose a bind target" else binds += BindAssignment(ref, bind)
                "field" -> {
                    if (field.isEmpty() && field2.isEmpty()) errors += "Choose a field"
                    val transforms = when (transform) { "", "default" -> null; "none" -> emptyList(); else -> listOf(transform) }
                    listOf(field, field2).filter { it.isNotEmpty() }.forEach {
                        fields += FieldAssignment(ref, it, transforms, stripPrefix)
                    }
                }
            }
        }
        val constants = mutableListOf<Constant>()
        for (i in 1..4) {
            val name = text("constName_$i", 48)
            val value = text("constValue_$i", 2000)
            if (name.isEmpty() && value.isEmpty()) continue
            val available = if (shapeRefresh) V.SHAPES.flatMap { constantFields(it) } else constantFields(shape)
            val spec = available.firstOrNull { it.name == name }
            val primitive = when (spec?.type) {
                FieldType.STRING -> JsonPrimitive(value)
                FieldType.INT -> value.toIntOrNull()?.let { JsonPrimitive(it) }
                FieldType.BOOLEAN -> value.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                else -> null
            }
            if (primitive == null) errors += "Invalid constant in row $i" else constants += Constant(name, primitive)
        }
        if (anchors.size + fields.size + binds.size + redacts.size + constants.size > 64) errors += "At most 64 active assignments"
        if (errors.isNotEmpty()) return DraftDecode.Errors(errors.distinct())
        return DraftDecode.Ok(DraftInput(Selections(screenClass, shape, intent, requireNotNull(priority), mode, surface,
            anchors, fields, binds, redacts, constants, comment), requireNotNull(envelopeId), pin, notes))
    }

    /** Encodes the subset represented by the form: one role, at most two fields and one transform per row. */
    fun encode(selections: Selections): Parameters = ParametersBuilder().apply {
        append("screenClass", selections.screenClass); append("shape", selections.shape)
        append("intent", selections.intent); append("priority", selections.priority.toString())
        append("modeHint", selections.modeHint.orEmpty()); append("offerSurface", selections.offerSurface.orEmpty())
        append("comment", selections.comment.orEmpty())
        for ((n, node) in nodes) {
            val ref = NodeRef(node.path)
            val fields = selections.fields.filter { it.node == ref }
            val bind = selections.binds.firstOrNull { it.node == ref }
            val role = when { ref in selections.anchors -> "anchor"; fields.isNotEmpty() -> "field"
                bind != null -> "bind"; ref in selections.redacts -> "redact"; else -> "" }
            append("role_$n", role)
            append("field_$n", fields.getOrNull(0)?.field.orEmpty()); append("field2_$n", fields.getOrNull(1)?.field.orEmpty())
            append("transform_$n", fields.firstOrNull()?.transform?.let { if (it.isEmpty()) "none" else it.single() } ?: "default")
            append("stripPrefix_$n", fields.firstOrNull()?.stripPrefix.orEmpty()); append("bind_$n", bind?.target.orEmpty())
        }
        selections.constants.forEachIndexed { i, constant ->
            append("constName_${i + 1}", constant.name); append("constValue_${i + 1}", constant.value.content)
        }
    }.build()

    companion object {
        // Six controls per node, plus header/constants/pin/TOTP: comfortably below 1,000 parameters.
        const val MAX_ROWS = 150
        val PIN = Regex("[0-9a-f]{4}(?:-[0-9a-f]{4}){3}")
        private val ROW_KEY = Regex("(?:role|field|field2|transform|stripPrefix|bind)_([0-9]+)")
        fun rows(walked: List<WalkedNode>, frame: RenderedWireframe): List<DraftNode> {
            val byPath = walked.associateBy { it.path }
            return frame.boxes.mapIndexedNotNull { index, box ->
                byPath[box.path]?.takeIf { it.viewId != null || it.text != null || it.desc != null || it.clickable }
                    ?.let { DraftNode(index + 1, it) }
            }
        }
        fun constantFields(shape: String): List<FieldSpec> = V.FIELDS_BY_SHAPE[shape].orEmpty().filter {
            it.type in setOf(FieldType.BOOLEAN, FieldType.INT, FieldType.STRING) && it.kind == FieldKind.PLAIN
        }

        /** Persist semantic paths, never display badge numbers or secrets. */
        fun selectionsJson(input: DraftInput, sha256: String): JsonObject = buildJsonObject {
            val s = input.selections
            fun ref(node: NodeRef) = JsonArray(node.path.map { JsonPrimitive(it) })
            put("screenClass", s.screenClass); put("shape", s.shape); put("intent", s.intent); put("priority", s.priority)
            s.modeHint?.let { put("modeHint", it) }; s.offerSurface?.let { put("offerSurface", it) }
            s.comment?.let { put("comment", it) }
            put("anchors", JsonArray(s.anchors.map { ref(it) }))
            put("redacts", JsonArray(s.redacts.map { ref(it) }))
            put("fields", JsonArray(s.fields.map { field -> buildJsonObject {
                put("node", ref(field.node)); put("field", field.field)
                field.transform?.let { put("transform", JsonArray(it.map { name -> JsonPrimitive(name) })) }
                field.stripPrefix?.let { put("stripPrefix", it) }
            } }))
            put("binds", JsonArray(s.binds.map { bind -> buildJsonObject { put("node", ref(bind.node)); put("target", bind.target) } }))
            put("constants", JsonArray(s.constants.map { constant -> buildJsonObject { put("name", constant.name); put("value", constant.value) } }))
            put("envelopeId", input.envelopeId); put("envelopeSha256", sha256); put("notes", input.notes)
        }
    }
}
