package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ingest.SlotWalk
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** #87: the tapped-button views behind the operator gate, k-gated like `/ops/rules`. */
class ClickedTapsViewTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private fun opsConfig() = config().copy(operatorTokenSha256 = hashSecret(operatorToken))

    private suspend fun HttpClient.ops(path: String): HttpResponse = get(path) {
        header(HttpHeaders.Authorization, "Bearer $operatorToken")
        header(HttpHeaders.Accept, "text/html")
    }

    /** The table row of the target at [ownerPath] in the layout whose id starts with [layoutPrefix]. */
    private fun row(page: String, layoutPrefix: String, ownerPath: String): String =
        page.split("<tr").single { it.contains("<code>$ownerPath</code>") && it.contains("<code>$layoutPrefix</code>") }

    @Test fun `a target is blinded below k, unblinded at k, and a trusted sighting unblinds its own target`() {
        Database.connect(config()).use { db ->
            val first = install()
            val second = install()
            val own = install(trusted = true)
            val button = clickedAccepted(day)
            val wrapped = clickedAccepted(day, extraWrapper = true)
            val root = clickedAccepted(day, ownerPath = "root", tapPath = "root/0")
            kotlinx.coroutines.runBlocking {
                // The button on the plain layout: one community install. The button on the wrapped layout: two.
                // The root: the trusted install only.
                observe(db, first, listOf(button, wrapped))
                observe(db, second, listOf(wrapped))
                observe(db, own, listOf(root))
            }
            val fingerprint = button.item.fingerprint
            assertEquals(fingerprint, wrapped.item.fingerprint)
            val plainLayout = SlotWalk.layout(button.item).take(8)
            val wrappedLayout = SlotWalk.layout(wrapped.item).take(8)

            testApplication {
                application { module(opsConfig(), db, clock, policy = policy.copy(k = 2)) }
                val browser = createClient { followRedirects = false }
                // Without a session a browser goes to the login form; an API caller gets the JSON 401.
                val redirect = browser.get("/ops/taps") { header(HttpHeaders.Accept, "text/html") }
                assertEquals(303, redirect.status.value)
                assertEquals("/ops/login", redirect.headers[HttpHeaders.Location])
                assertError(browser.get("/ops/taps/view?fingerprint=$fingerprint"), 401, "unauthorized")

                val response = client.ops("/ops/taps/view?fingerprint=$fingerprint")
                assertEquals(200, response.status.value)
                assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
                    response.headers["Content-Security-Policy"])
                val page = response.bodyAsText()
                // Three targets: one owner path in two layouts stays two buttons, and the root is a third.
                assertTrue(page.contains("3 buttons"))
                // Below k: one non-trusted install, no trusted one; the owner's class and id are blinded.
                val blinded = row(page, plainLayout, "root/1")
                assertTrue(blinded.contains("class=\"redacted\" aria-label=\"Redacted class, ~"), blinded)
                assertFalse(blinded.contains("android.view.View"))
                assertTrue(blinded.contains("Redacted: 1 / 2 non-trusted installs"))
                assertTrue(blinded.contains("click action"))
                // At k: two non-trusted installs within one epoch.
                val counted = row(page, wrappedLayout, "root/1")
                assertTrue(counted.contains("android.view.View"), counted)
                assertFalse(counted.contains("class=\"redacted\""))
                assertTrue(counted.contains("Visible: 2"))
                // The trusted install's own tap: no non-trusted support, unblinded by its trusted sighting.
                val trusted = row(page, plainLayout, "root")
                assertTrue(trusted.contains("android.widget.FrameLayout"), trusted)
                assertTrue(trusted.contains("Visible: trusted sighting."))
                assertTrue(trusted.contains(">clickable<"))
                // No hash, public or keyed, ever renders.
                assertFalse(page.contains("0123456789abcdef"))
                assertFalse(page.contains(testKeying.token("0123456789abcdef")))
                // No cluster holds the fingerprint yet, so there is no cluster link.
                assertFalse(page.contains("href=\"/ops/clusters/$fingerprint/view\""))

                // The list: one screen, two layouts, three buttons, the most installs one button had.
                val list = client.ops("/ops/taps")
                assertEquals(200, list.status.value)
                val listPage = list.bodyAsText()
                assertTrue(listPage.contains("href=\"/ops/taps/view?fingerprint=$fingerprint\""), listPage)
                val screenRow = listPage.split("<tr").single { it.contains("/ops/taps/view?fingerprint=$fingerprint") }
                assertEquals(listOf("2", "3", "2"), Regex("<span class=\"cell-value\">([0-9]+)</span>")
                    .findAll(screenRow).map { it.groupValues[1] }.toList())
                // The dashboard links the page.
                assertTrue(client.ops("/ops/").bodyAsText().contains("href=\"/ops/taps\""))

                // A cluster with the fingerprint is linked from the screen's page.
                sql { it.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'doordash', ?, ?)", fingerprint, day, day) }
                assertTrue(client.ops("/ops/taps/view?fingerprint=$fingerprint").bodyAsText().contains("href=\"/ops/clusters/$fingerprint/view\""))

                // A malformed or missing fingerprint is the invalid shell; a well-formed one without samples is 404.
                for (path in listOf("/ops/taps/view?fingerprint=XYZ", "/ops/taps/view", "/ops/taps/view?fingerprint=${fingerprint.uppercase()}")) {
                    val invalid = client.ops(path)
                    assertEquals(400, invalid.status.value, path)
                    assertTrue(invalid.bodyAsText().contains("Invalid screen"), path)
                }
                val missing = client.ops("/ops/taps/view?fingerprint=${"e".repeat(64)}")
                assertEquals(404, missing.status.value)
                assertTrue(missing.bodyAsText().contains("Screen not found"))
            }
        }
    }

    @Test fun `the tap list renders empty before any clicked sample arrives`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(opsConfig(), db, clock, policy = policy) }
            val page = client.ops("/ops/taps")
            assertEquals(200, page.status.value)
            assertTrue(page.bodyAsText().contains("No clicked samples retained."))
        } }
    }
}
