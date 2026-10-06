package cloud.trotter.census.server.ingest

import kotlinx.serialization.json.*
import java.time.LocalDate

/** Receipt accounting and observation age are deliberately separate. */
object LifecycleDeadline {
    fun observation(day: LocalDate, receipt: LocalDate): LocalDate = minOf(day, receipt)
    fun expired(deadline: LocalDate, today: LocalDate): Boolean = deadline <= today
    fun sample(lastObservation: LocalDate, resolvedDay: LocalDate?): LocalDate =
        minOf(lastObservation.plusDays(90), resolvedDay?.plusDays(30) ?: LocalDate.MAX)
}

/** Pure, idempotent server-only rewrite. Fingerprints and all non-slot metadata are unchanged. */
object StoredSampleExpiry {
    fun rewrite(body: String, liveHashes: Set<String>): String {
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> if ((value["h"] as? JsonPrimitive)?.isString == true && "kind" in value &&
                (value["h"] as JsonPrimitive).content !in liveHashes) {
                buildJsonObject { put("kind", "expired") }
            } else JsonObject(value.mapValues { visit(it.value) })
            is JsonArray -> JsonArray(value.map { visit(it) })
            else -> value
        }
        return visit(Json.parseToJsonElement(body)).toString()
    }
}
