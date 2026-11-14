package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.QuarantineEntry
import kotlinx.html.code
import kotlinx.html.h1
import kotlinx.html.p

/**
 * The quarantine page (#81): the one operator view that lists "Ask the developer" items. It shows ids, expiry days,
 * notes and sizes; the content is ciphertext only the operator's offline key opens (docs/OPERATOR.md).
 */
object QuarantineHtml {
    fun render(serverVersion: String, k: Int, today: String, items: List<QuarantineEntry>): String =
        opsPage(pageTitle = "Quarantine · Census operator", headerContent = {
            h1 { +"Quarantine" }
            metadata(serverVersion, k, today)
        }) {
            panel("quarantine", "Ask the developer", badge = number(items.size)) {
                p("muted") {
                    +"Items expire at the start of their expiry day (UTC), at most 72 hours after receipt. Download one with "
                    code { +"GET /ops/quarantine/{id}" }
                    +" and open it offline with deploy/tools/QuarantineKey.java. Nothing here is clustered, counted or drafted from."
                }
                if (items.isEmpty()) emptyState("No items waiting.")
                else dataTable("Quarantine items · soonest to expire first", listOf("Item", "Expires", "Note", "Bytes"), items.map { item ->
                    listOf(
                        cell { code { +item.id.toString() } },
                        cell { date(item.expiresDay.toString()) },
                        cell { +(item.note?.wire ?: "none") },
                        numeric(item.bytes),
                    )
                })
            }
        }
}
