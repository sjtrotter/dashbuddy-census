package cloud.trotter.census.server

import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ops.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class OpsAuthTest {
    private val token = "OPERATOR_TOKEN_SENTINEL"
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val instant = Instant.parse("2026-10-02T12:00:00Z")
    private val clock = object : Clock { override fun now(): Instant = instant }
    private fun config() = Config.fromEnv(testEnvironment() + mapOf("OPERATOR_TOKEN_SHA256" to hashSecret(token), "OPERATOR_TOTP_SECRET" to secret))
    private val path = "/ops/clusters/${"a".repeat(64)}/draft"

    @Test
    fun `form shares replay gate with header and conflict never spends code`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { route("/ops") {
                install(OpsAuth) { config = this@OpsAuthTest.config(); clock = this@OpsAuthTest.clock; sessions = OpsSessions(); replay = TotpReplay() }
                post("/{rest...}") { call.respondText("accepted") }
            } }
        }
        suspend fun send(body: String, headerCode: String? = null, target: String = path) = client.post(target) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (headerCode != null) header("X-Census-Totp", headerCode)
            contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }
        val code = Totp.code(secret, instant.epochSecond)
        assertEquals(400, send("totp=$code", code).status.value)
        assertEquals(200, send("totp=$code").status.value)
        assertEquals(401, send("totp=$code").status.value)
        assertEquals(401, send("", code).status.value)
        val save = send("")
        assertEquals(401, save.status.value); assertTrue(save.bodyAsText().contains("totp_required"))
        assertEquals(200, send("", target = "$path/preview").status.value)
        assertEquals(200, send("", target = "$path/shape").status.value)
        assertEquals(401, send("", target = "$path/preview/extra").status.value)
    }

    @Test
    fun `header form and login all consume the same replay verifier`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val code = Totp.code(secret, instant.epochSecond)
        val headerAccepted = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token"); header("X-Census-Totp", code)
            contentType(ContentType.Application.FormUrlEncoded); setBody("notes=review")
        }
        // Passing auth reaches the intentional database-unavailable gate.
        assertEquals(503, headerAccepted.status.value)
        assertTrue(headerAccepted.bodyAsText().contains("db_unavailable"))
        val formReplay = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.FormUrlEncoded); setBody("totp=$code")
        }
        assertEquals(401, formReplay.status.value)
        assertTrue(formReplay.bodyAsText().contains("totp_replayed"))
        val loginReplay = client.post("/ops/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("token" to token, "code" to code).formUrlEncode())
        }
        assertEquals(401, loginReplay.status.value)
    }

    @Test
    fun `forms use explicit byte parameter value duplicate and escape bounds with HTML errors`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        suspend fun send(body: String, target: String = "$path/preview") = client.post(target) {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }
        assertEquals(413, send("x=" + "x".repeat(70 * 1024)).status.value)
        for (body in listOf((1..1001).joinToString("&") { "p$it=" }, "x=" + "x".repeat(2001), "x=1&x=2", "x=1&%78=2", "x=%QQ")) {
            val response = send(body)
            assertEquals(400, response.status.value)
            assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
            assertPrivate(response.bodyAsText())
        }
        val json = send("x=1&x=2", "/ops/vocabulary/resolve")
        assertEquals(400, json.status.value)
        assertEquals(ContentType.Application.Json, json.contentType()?.withoutParameters())
        assertEquals(1000, parseOpsForm((1..1000).joinToString("&") { "p$it=" }).names().size)
        assertEquals("a + b", parseOpsForm("x=a+%2B+b")["x"])
    }

    @Test
    fun `multipart preview is rejected as HTML before the handler`() = testApplication {
        var reachedHandler = false
        application {
            module(config(), db = null, clock = clock)
            routing { route("/ops") {
                post("/clusters/{fingerprint}/draft/preview") {
                    reachedHandler = true
                    call.respondText("accepted")
                }
            } }
        }
        val valid = client.post("$path/preview") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.FormUrlEncoded); setBody("intent=home")
        }
        assertEquals(200, valid.status.value)
        assertTrue(reachedHandler)
        reachedHandler = false
        val response = client.post("$path/preview") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "ops-boundary"))
            setBody("--ops-boundary\r\nContent-Disposition: form-data; name=\"intent\"\r\n\r\nhome\r\n--ops-boundary--\r\n")
        }
        assertEquals(400, response.status.value)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertFalse(reachedHandler)
        assertPrivate(response.bodyAsText())
    }

    @Test
    fun `only exact decoded pure draft paths bypass TOTP`() {
        for (suffix in listOf("preview", "shape", "%70review")) assertTrue(isPureDraftPath("$path/$suffix"))
        for (p in listOf(path, "$path/preview/", "$path//preview", "$path/preview/extra", path.replace("a".repeat(64), "invalid") + "/preview")) assertFalse(isPureDraftPath(p))
        assertFalse(isPureDraftPath("/ops/%QQ"))
        for (p in listOf("$path%2Fpreview", "$path%2fshape", "$path/preview".replace("/clusters/", "/clusters%2F"))) {
            assertFalse(isPureDraftPath(p), p)
            assertFalse(isOpsHtmlPath(p), p)
        }
    }
}
