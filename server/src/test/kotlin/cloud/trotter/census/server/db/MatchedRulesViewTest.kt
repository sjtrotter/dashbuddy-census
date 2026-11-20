package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.auth.hashSecret
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** #69 (S11): the per-rule view behind the operator gate, grouped by layout, k-gated like the clusters. */
class MatchedRulesViewTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private fun opsConfig() = config().copy(operatorTokenSha256 = hashSecret(operatorToken))

    private suspend fun HttpClient.ops(path: String, html: Boolean = true): HttpResponse = get(path) {
        header(HttpHeaders.Authorization, "Bearer $operatorToken")
        if (html) header(HttpHeaders.Accept, "text/html")
    }

    @Test fun `the rule view groups layouts by newest sample, links the resolved cluster and blinds below k`() {
        Database.connect(config()).use { db ->
            val community = install()
            val own = install(trusted = true)
            val first = day
            // Layout X from one community install on two days; layout Y from the trusted install a day later.
            val x = matchedAccepted(day)
            kotlinx.coroutines.runBlocking {
                observe(db, community, listOf(x))
                instant = instant.plusSeconds(86_400)
                observe(db, community, listOf(matchedAccepted(day)))
                instant = instant.plusSeconds(86_400)
                observe(db, own, listOf(matchedAccepted(day, rootId = "com.example.app:id/summary")))
            }
            val xFingerprint = x.item.fingerprint
            val yFingerprint = matchedAccepted(day, rootId = "com.example.app:id/summary").item.fingerprint
            // The cluster the rule was drafted from holds layout X.
            sql { it.update("""INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day, status, resolved_day, resolved_rule_id)
                VALUES (?, 'doordash', ?, ?, 'resolved', ?, ?)""", xFingerprint, first, first, first, MATCHED_RULE) }

            testApplication {
                application { module(opsConfig(), db, clock, policy = policy) }
                val browser = createClient { followRedirects = false }
                // Without a session a browser goes to the login form; an API caller gets the JSON 401.
                val redirect = browser.get("/ops/rules/$MATCHED_RULE/view") { header(HttpHeaders.Accept, "text/html") }
                assertEquals(303, redirect.status.value)
                assertEquals("/ops/login", redirect.headers[HttpHeaders.Location])
                assertError(browser.get("/ops/rules/$MATCHED_RULE"), 401, "unauthorized")

                val response = client.ops("/ops/rules/$MATCHED_RULE/view")
                assertEquals(200, response.status.value)
                assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
                    response.headers["Content-Security-Policy"])
                val page = response.bodyAsText()
                val layoutY = page.substringAfter("id=\"layout-1\"").substringBefore("id=\"layout-2\"")
                val layoutX = page.substringAfter("id=\"layout-2\"").substringBefore("id=\"older\"")
                // Y has the newest sample, so it comes first; X is the resolved cluster's layout.
                assertTrue(layoutY.contains("New layout · ${yFingerprint.take(8)}"))
                assertTrue(layoutX.contains("Same layout as cluster ${xFingerprint.take(8)}"))
                // X: two samples, newest first; one community install is below k, so class and id are blinded.
                assertEquals(2, Regex("<article class=\"sample\"").findAll(layoutX).count())
                assertTrue(layoutX.indexOf(first.plusDays(1).toString()) < layoutX.indexOf("<time datetime=\"$first\""))
                assertTrue(layoutX.contains("class=\"redacted\" aria-label=\"Redacted class, ~"))
                assertFalse(layoutX.contains("android.widget.FrameLayout"))
                // Y: the trusted install's own sighting unblinds it.
                assertTrue(layoutY.contains("android.widget.FrameLayout"))
                assertFalse(layoutY.contains("class=\"redacted\""))
                // A shape slot shows its template; slot classes read unknown below k.
                assertTrue(page.contains("shape: \$#.##"))
                assertTrue(page.contains(">unknown<"))
                // The older layouts link the resolved cluster's page.
                assertTrue(page.substringAfter("id=\"older\"").contains("href=\"/ops/clusters/$xFingerprint/view\""))
                // No hash, public or keyed, ever renders.
                assertFalse(page.contains("0123456789abcdef"))
                assertFalse(page.contains(testKeying.token("0123456789abcdef")))

                // The JSON view carries the same projection: kinds, never hashes.
                val json = Json.parseToJsonElement(client.ops("/ops/rules/$MATCHED_RULE", html = false).bodyAsText()).jsonObject
                val layouts = json.getValue("layouts").jsonArray.map { it.jsonObject }
                assertEquals(listOf(yFingerprint, xFingerprint), layouts.map { it.getValue("fingerprint").jsonPrimitive.content })
                assertEquals(listOf(false, true), layouts.map { it.getValue("sameAsCluster").jsonPrimitive.boolean })
                assertTrue(json.toString().contains("\"shape:\$#.##\""))
                assertFalse(json.toString().contains(testKeying.token("0123456789abcdef")))
                assertEquals(listOf(xFingerprint), json.getValue("olderLayouts").jsonArray.map { it.jsonObject.getValue("fingerprint").jsonPrimitive.content })

                // The rule list: one rule, two layouts, the newest receipt.
                val rules = Json.parseToJsonElement(client.ops("/ops/rules", html = false).bodyAsText()).jsonArray.single().jsonObject
                assertEquals(MATCHED_RULE, rules.getValue("ruleId").jsonPrimitive.content)
                assertEquals(2, rules.getValue("layouts").jsonPrimitive.int)
                assertEquals(1, rules.getValue("installs").jsonPrimitive.int)
                assertEquals(day.toString(), rules.getValue("newestReceivedDay").jsonPrimitive.content)
                assertTrue(client.ops("/ops/rules/view").bodyAsText().contains("href=\"/ops/rules/$MATCHED_RULE/view\""))

                // The resolved cluster's page links to the rule's matched samples.
                assertTrue(client.ops("/ops/clusters/$xFingerprint/view").bodyAsText().contains("href=\"/ops/rules/$MATCHED_RULE/view\""))

                // A malformed rule id is the invalid shell; a well-formed one without samples is 404.
                for (path in listOf("/ops/rules/Bad.Id/view", "/ops/rules/doordash/view")) {
                    val invalid = client.ops(path)
                    assertEquals(400, invalid.status.value, path)
                    assertTrue(invalid.bodyAsText().contains("Invalid rule"), path)
                }
                val missing = client.ops("/ops/rules/doordash.screen.none/view")
                assertEquals(404, missing.status.value)
                assertTrue(missing.bodyAsText().contains("Rule not found"))
                assertEquals(400, client.ops("/ops/rules/Bad.Id", html = false).status.value)
                assertError(client.ops("/ops/rules/doordash.screen.none", html = false), 404, "not_found")
            }
        }
    }

    @Test fun `the rule list renders empty before any matched sample arrives`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(opsConfig(), db, clock, policy = policy) }
            val page = client.ops("/ops/rules/view")
            assertEquals(200, page.status.value)
            assertTrue(page.bodyAsText().contains("No matched samples retained."))
            assertEquals("[]", client.ops("/ops/rules", html = false).bodyAsText())
        } }
    }
}
