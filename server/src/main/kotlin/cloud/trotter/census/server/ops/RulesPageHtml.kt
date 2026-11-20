package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.OpsMatchedRule
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.h1
import kotlinx.html.p

/** The matched-skeleton rules (#69): one row per rule with a live matched sample, the newest receipt first. */
object RulesPageHtml {
    fun render(serverVersion: String, k: Int, today: String, rules: List<OpsMatchedRule>): String = opsPage(
        pageTitle = "Matched rules · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to the dashboard" }
        },
    ) {
        h1 { +"Matched rules" }
        p("muted") {
            +("Screens a recognition rule matched while it was failing to read them on the phone, tagged with the rule. " +
                "Installs are the most non-trusted installs one layout of the rule was seen by within one epoch in 28 days.")
        }
        if (rules.isEmpty()) emptyState("No matched samples retained.")
        else dataTable(
            "Rules with matched samples",
            listOf("Rule", "Platform", "Layouts", "Versions", "Installs", "Newest sample"),
            rules.map { row ->
                listOf(
                    cell {
                        val id = rule(row.ruleId)
                        if (id == row.ruleId) a(href = "/ops/rules/$id/view") { code { +id } } else code { +id }
                    },
                    cell { +platform(row.platform) },
                    numeric(row.layouts),
                    cell { +row.versions.joinToString(", ") { version(it) }.ifEmpty { "None recorded" } },
                    numeric(row.installs),
                    cell { date(row.newestReceivedDay) },
                )
            },
        )
    }
}
