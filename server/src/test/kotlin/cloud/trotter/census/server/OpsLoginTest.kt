package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ops.Totp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant

class OpsLoginTest {
    private var instant = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = object : Clock { override fun now(): Instant = instant }
    private val token = "OPERATOR_TOKEN_SENTINEL"
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private fun config(): Config = Config.fromEnv(testEnvironment() + mapOf(
        "OPERATOR_TOKEN_SHA256" to hashSecret(token), "OPERATOR_TOTP_SECRET" to secret,
    ))
    private fun code(): String = Totp.code(secret, instant.epochSecond)

    @Test
    fun `login page is public bounded to same-origin forms and has no scripts`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val response = client.get("/ops/login")
        assertEquals(200, response.status.value)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'", response.headers["Content-Security-Policy"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val page = response.bodyAsText()
        for (expected in listOf("<title>census · sign in</title>", "<form", "action=\"/ops/login\"", "method=\"post\"", "name=\"token\"", "name=\"code\"", "autocomplete=\"off\"", "autocomplete=\"one-time-code\"", "pattern=\"[0-9]{6}\"", "maxlength=\"1024\"", "maxlength=\"6\"")) {
            assertTrue(page.contains(expected))
        }
        assertFalse(page.contains("<script"))
        assertFalse(page.contains(token))
    }

    @Test
    fun `wrong token does not consume a valid code or echo either credential`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val failed = browser.login(token = "WRONG_TOKEN_SENTINEL")
        assertFailed(failed)
        assertFalse(failed.bodyAsText().contains("WRONG_TOKEN_SENTINEL"))
        assertFalse(failed.bodyAsText().contains(code()))
        assertSessionCookie(browser.login())
    }

    @Test
    fun `wrong and replayed codes fail generically while success sets a hardened cookie`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val wrongCode = (0..999999).asSequence().map { it.toString().padStart(6, '0') }
            .first { !Totp.matches(secret, it, instant) }
        assertFailed(browser.login(code = wrongCode))
        assertSessionCookie(browser.login())
        assertFailed(browser.login())
        instant = instant.plusSeconds(30)
        assertSessionCookie(browser.login())
    }

    @Test
    fun `cookie authenticates reads but mutations still require a fresh code before the database gate`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val cookie = assertSessionCookie(browser.login())
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, cookie) }, 503, "db_unavailable")
        // The dashboard also needs the database; its HTML and logout form are covered with a store in OpsRoutesTest.
        assertError(browser.get("/ops/") { header(HttpHeaders.Cookie, cookie) }, 503, "db_unavailable")
        assertError(browser.post("/ops/installs/x/trust") { header(HttpHeaders.Cookie, cookie) }, 401, "totp_required")
        assertError(browser.post("/ops/installs/x/trust") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Census-Totp", code())
        }, 401, "totp_replayed")
        instant = instant.plusSeconds(30)
        assertError(browser.post("/ops/installs/x/trust") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Census-Totp", code())
        }, 503, "db_unavailable")
        assertFailed(browser.login(), "a mutation's code must not be reusable for login")
        val redirect = browser.get("/ops/login") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(303, redirect.status.value)
        assertEquals("/ops/", redirect.headers[HttpHeaders.Location])
    }

    @Test
    fun `an explicit bad authorization header outranks a valid cookie`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val cookie = assertSessionCookie(browser.login())
        for (authorization in listOf("Bearer WRONG_TOKEN_SENTINEL", "Basic invalid", "Bearer ")) {
            assertError(browser.get("/ops/clusters") {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Authorization, authorization)
            }, 401, "unauthorized")
        }
        assertError(browser.get("/ops/clusters") {
            header(HttpHeaders.Cookie, "census_ops=invalid")
            header(HttpHeaders.Authorization, "Bearer $token")
        }, 503, "db_unavailable")
    }

    @Test
    fun `logout needs authentication but no code and revokes the old cookie`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        assertError(browser.post("/ops/logout"), 401, "unauthorized")
        val cookie = assertSessionCookie(browser.login())
        val response = browser.post("/ops/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(303, response.status.value)
        assertEquals("/ops/login", response.headers[HttpHeaders.Location])
        assertCookieAttributes(requireNotNull(response.headers[HttpHeaders.SetCookie]), 0)
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, cookie) }, 401, "unauthorized")
    }

    @Test
    fun `new login replaces the previous browser session`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val first = assertSessionCookie(browser.login())
        instant = instant.plusSeconds(30)
        val second = assertSessionCookie(browser.login())
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, first) }, 401, "unauthorized")
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, second) }, 503, "db_unavailable")
    }

    @Test
    fun `login fails closed without TOTP configuration but the page stays available`() = testApplication {
        application { module(config().copy(operatorTotpSecret = null), db = null, clock = clock) }
        assertEquals(200, client.get("/ops/login").status.value)
        assertError(client.login(), 503, "totp_unconfigured")
    }

    @Test
    fun `oversized forms invalid fields and other content types are rejected`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        // "%ZZ" and a trailing "%" raise Ktor's checked URLDecodeException: a 400, never a 500 whose message embeds the body.
        for (body in listOf("x".repeat(5000), "code=123456", "token=x", "token=${"a".repeat(1025)}&code=123456", "token=x&code=12345", "token=x&code=abcdef", "token=%ZZ&code=123456", "token=abc%")) {
            assertError(client.post("/ops/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }, 400, "bad_request")
        }
        assertError(client.post("/ops/login") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }, 400, "bad_request")
        // No refusal path ever sets a cookie. (A malformed Content-Type is mapped to 400 by StatusPages; the test
        // client refuses to send one, so that mapping is exercised by the URLDecodeException sibling above.)
        val refused = client.post("/ops/login") { contentType(ContentType.Application.FormUrlEncoded); setBody("token=x&code=12345") }
        assertEquals(400, refused.status.value)
        assertNull(refused.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `login caps raw bytes before decoding and preserves first duplicate value`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val valid = listOf("token" to token, "code" to code()).formUrlEncode()
        val oversized = browser.post("/ops/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(valid + "&padding=" + "%20".repeat(1400))
        }
        assertError(oversized, 400, "bad_request")
        assertNull(oversized.headers[HttpHeaders.SetCookie])
        // Unknown values have no draft-only 2,000-character cap; login retains its raw 4 KiB cap.
        assertSessionCookie(browser.post("/ops/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(valid + "&token=wrong&code=000000&padding=" + "x".repeat(2001))
        })
        instant = instant.plusSeconds(30)
        assertFailed(browser.post("/ops/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("token" to "wrong", "token" to token, "code" to code()).formUrlEncode())
        })
        assertSessionCookie(browser.login())
    }

    @Test
    fun `a token with spaces or a literal plus authenticates through the form exactly as it does as a bearer`() = testApplication {
        val spaced = "operator pass+phrase"
        application { module(Config.fromEnv(testEnvironment() + mapOf("OPERATOR_TOKEN_SHA256" to hashSecret(spaced), "OPERATOR_TOTP_SECRET" to secret)), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Authorization, "Bearer $spaced") }, 503, "db_unavailable")
        // The browser encodes the space as `+` and the literal plus as %2B (formUrlEncode does the same).
        val cookie = assertSessionCookie(browser.login(token = spaced))
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, cookie) }, 503, "db_unavailable")
        instant = instant.plusSeconds(30) // a fresh code: the replay memory refuses the one just spent on login
        assertSessionCookie(browser.login(token = spaced))
    }

    @Test
    fun `login exemption is restricted to the exact path and GET or POST`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        for (path in listOf("/ops/login/extra", "/ops/logout", "/ops/unknown", "/ops/login/", "/ops/%6Cogin")) {
            assertError(client.get(path), 401, "unauthorized")
        }
        for (method in listOf(HttpMethod.Put, HttpMethod.Head, HttpMethod.Options)) {
            assertEquals(401, client.request("/ops/login") { this.method = method }.status.value, method.value)
        }
        // A malformed cookie value is a 400, not a 500 carrying the cookie in an exception message.
        assertError(client.get("/ops/clusters") { header(HttpHeaders.Cookie, "census_ops=%ZZ") }, 400, "bad_request")
    }

    @Test
    fun `a browser without a session is sent to the form while API callers get the JSON 401 and reads are never cached`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        val redirect = browser.get("/ops/") { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }
        assertEquals(303, redirect.status.value)
        assertEquals("/ops/login", redirect.headers[HttpHeaders.Location])
        assertEquals("no-store", redirect.headers[HttpHeaders.CacheControl])
        assertError(browser.get("/ops/clusters"), 401, "unauthorized")
        // A wrong bearer is a refusal even for an HTML client: never a redirect, never a cookie (the JSON error body
        // meets ContentNegotiation's 406 for a text/html Accept — either way, no 303 and no Location).
        val wrongBearer = browser.get("/ops/") { header(HttpHeaders.Authorization, "Bearer WRONG"); header(HttpHeaders.Accept, "text/html") }
        assertTrue(wrongBearer.status.value in setOf(401, 406), wrongBearer.status.toString())
        assertNull(wrongBearer.headers[HttpHeaders.Location])
        assertNull(wrongBearer.headers[HttpHeaders.SetCookie])
        val cookie = assertSessionCookie(browser.login())
        val read = browser.get("/ops/clusters") { header(HttpHeaders.Cookie, cookie) }
        assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
        // An expired session through HTTP: the form is served, no redirect to the dashboard.
        instant = instant.plusSeconds(61 * 60)
        val form = browser.get("/ops/login") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(200, form.status.value)
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, cookie) }, 401, "unauthorized")
        // A bearer caller can revoke the browser session; the success redirect carries no-store too.
        val fresh = browser.login()
        assertEquals("no-store", fresh.headers[HttpHeaders.CacheControl])
        val freshCookie = assertSessionCookie(fresh)
        assertEquals(303, browser.post("/ops/logout") { header(HttpHeaders.Authorization, "Bearer $token") }.status.value)
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Cookie, freshCookie) }, 401, "unauthorized")
    }

    @Test
    fun `login failures spend the shared bucket and rejection never consumes a valid code`() = testApplication {
        application { module(config(), db = null, clock = clock) }
        val browser = createClient { followRedirects = false }
        repeat(60) { assertEquals(401, browser.login(token = "WRONG_TOKEN_SENTINEL").status.value) }
        val limited = browser.login()
        assertError(limited, 429, "rate_limited")
        assertEquals("1", limited.headers[HttpHeaders.RetryAfter])
        assertError(browser.get("/ops/clusters") { header(HttpHeaders.Authorization, "Bearer $token") }, 429, "rate_limited")
        instant = instant.plusSeconds(1)
        assertSessionCookie(browser.login())
    }

    @Test
    fun `login and logout log only method known path and status`() {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            testApplication {
                environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
                application { module(config(), db = null, clock = clock) }
                startApplication()
                logs.list.clear()
                val browser = createClient { followRedirects = false }
                assertEquals(200, browser.get("/ops/login?token=QUERY_SENTINEL").status.value)
                assertFailed(browser.login(token = "WRONG_TOKEN_SENTINEL"))
                val cookie = assertSessionCookie(browser.login())
                assertEquals(303, browser.post("/ops/logout") { header(HttpHeaders.Cookie, cookie) }.status.value)
                val infos = logs.list.filter { it.level == Level.INFO && it.formattedMessage.contains("method=") }
                assertEquals(4, infos.size)
                assertTrue(infos.all { it.loggerName == "Ops" })
                assertTrue(infos.all { Regex("ops method=(GET|POST) path=/ops/(login|logout) status=(200|303|401)").matches(it.formattedMessage) })
                assertPrivateLogs(logs.list, listOf(token, secret, code(), cookie.substringAfter('='), "QUERY_SENTINEL", "WRONG_TOKEN_SENTINEL"))
            }
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    private suspend fun HttpClient.login(token: String = this@OpsLoginTest.token, code: String = this@OpsLoginTest.code()): HttpResponse = post("/ops/login") {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(listOf("token" to token, "code" to code).formUrlEncode())
    }

    private suspend fun assertFailed(response: HttpResponse, message: String = "sign-in must fail generically") {
        assertEquals(401, response.status.value, message)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertTrue(response.bodyAsText().contains("Sign-in failed."))
        assertFalse(response.bodyAsText().contains(token))
        assertNull(response.headers[HttpHeaders.SetCookie])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    private fun assertSessionCookie(response: HttpResponse): String {
        assertEquals(303, response.status.value)
        assertEquals("/ops/", response.headers[HttpHeaders.Location])
        val cookie = requireNotNull(response.headers[HttpHeaders.SetCookie])
        assertCookieAttributes(cookie, 43200)
        return cookie.substringBefore(';').also { assertTrue(Regex("census_ops=[A-Za-z0-9_-]{43}").matches(it)) }
    }

    private fun assertCookieAttributes(cookie: String, maxAge: Int) {
        val attributes = cookie.split(';').drop(1).map { it.trim().lowercase() }
        for (attribute in listOf("httponly", "secure", "samesite=strict", "path=/ops", "max-age=$maxAge")) {
            assertTrue(attribute in attributes)
        }
    }
}
