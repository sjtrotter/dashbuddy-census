package cloud.trotter.census.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Exercises the public routes with no database or Docker dependency (#1157 S1). */
class HealthRoutesTest {
    @Test
    fun `unknown methods are logged as OTHER`() = withCapturedLogs { logs ->
        testApplication {
            environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
            application { module(Config.fromEnv(testEnvironment()), db = null) }
            client.request("/healthz") { method = HttpMethod("BEARERSECRET") }.bodyAsText()
        }
        assertTrue(logs.list.any { it.formattedMessage.contains("method=OTHER") })
        assertFalse(logs.list.any { it.formattedMessage.contains("BEARERSECRET") })
    }

    @Test
    fun `authorization headers and query secrets are never logged`() = withCapturedLogs { logs ->
        testApplication {
            environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
            application { module(Config.fromEnv(testEnvironment()), db = null) }
            val response = client.get("/healthz?token=PRIVATE_QUERY_SENTINEL") {
                header(HttpHeaders.Authorization, "Bearer PRIVATE_AUTH_SENTINEL")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            response.bodyAsText()
        }
        assertTrue(logs.list.any { it.formattedMessage.contains("method=GET path=/healthz") })
        logs.list.forEach {
            assertFalse(it.formattedMessage.contains("PRIVATE_QUERY_SENTINEL"))
            assertFalse(it.formattedMessage.contains("PRIVATE_AUTH_SENTINEL"))
            assertFalse(it.formattedMessage.contains("?token="))
            assertFalse(it.formattedMessage.contains("Bearer "))
        }
    }

    @Test
    fun `unhandled exceptions return a stable error without private messages`() = withCapturedLogs { logs ->
        testApplication {
            environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
            application {
                module(Config.fromEnv(testEnvironment()), db = null)
                routing {
                    get("/throw") { throw IllegalStateException("PRIVATE_MESSAGE_SENTINEL") }
                }
            }
            val response = client.get("/throw")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertEquals("{\"error\":\"internal_error\"}", body)
            assertFalse(body.contains("PRIVATE_MESSAGE_SENTINEL"))
        }
        assertTrue(logs.list.any { it.formattedMessage.contains("status=500") })
        logs.list.forEach {
            assertFalse(it.formattedMessage.contains("PRIVATE_MESSAGE_SENTINEL"))
            assertFalse(it.throwableProxy?.message?.contains("PRIVATE_MESSAGE_SENTINEL") == true)
        }
    }

    @Test
    fun `health is live and readiness is unavailable without a database`() = testApplication {
        application { module(Config.fromEnv(testEnvironment()), db = null) }
        val health = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", Json.parseToJsonElement(health.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
        val ready = client.get("/readyz")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(ready.bodyAsText().contains("db_unavailable"))
        assertTrue(health.headers[HttpHeaders.Server].isNullOrBlank())
    }

    @Test
    fun `policy exposes defaults and no credentials`() = testApplication {
        application { module(Config.fromEnv(testEnvironment()), db = null) }
        val response = client.get("/v1/policy")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        val policy = Json.parseToJsonElement(body).jsonObject
        assertEquals("10", policy["k"]?.jsonPrimitive?.content)
        assertTrue(policy.getValue("acceptedSchemaIds").jsonArray.any { it.jsonPrimitive.content == "uinode.skeleton.v1" })
        assertEquals("30", policy.getValue("retention").jsonObject["tokenSightingsDays"]?.jsonPrimitive?.content)

        // The required tokenSightingsDays key is the sole exception to "no token".
        // A literal substring ban on the unmodified body contradicts the wire policy.
        val redactionCheck = body.replace("tokenSightingsDays", "").lowercase()
        listOf("password", "token", "secret").forEach { assertFalse(redactionCheck.contains(it)) }
        testEnvironment().values.forEach { assertFalse(body.contains(it)) }
    }

    @Test
    fun `unknown routes have a stable JSON error`() = testApplication {
        application { module(Config.fromEnv(testEnvironment()), db = null) }
        val response = client.get("/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content)
    }

    private fun withCapturedLogs(test: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("io.ktor.server.Application") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            test(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
