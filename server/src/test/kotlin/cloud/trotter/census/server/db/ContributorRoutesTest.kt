package cloud.trotter.census.server.db

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.QuarantineCrypto
import cloud.trotter.census.server.assertError
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.db.SlotRelease.buildRelease
import cloud.trotter.census.server.enrol
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.module
import cloud.trotter.census.server.ops.Totp
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.signed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

/** #81: the contributor routes over HTTP, their error codes, the operator's routes, and logs that carry counts only. */
class ContributorRoutesTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val operator = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    private fun opsConfig(withKey: Boolean = true) = config().copy(operatorTokenSha256 = hashSecret(operatorToken), operatorTotpSecret = totpSecret,
        operatorPublicKey = if (withKey) QuarantineCrypto.publicKey(Base64.getEncoder().encodeToString(operator.public.encoded)) else null)

    private val wordText = "Pick up by"
    private val hash = requireNotNull(CensusHash.of(wordText))

    private fun item(): UiSkeletonDto {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot(hash, "words:3")))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", children = listOf(leaf))
        return UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = day.toString(), root = root)
    }

    private fun variant(): JsonObject = item().let {
        JsonObject(mapOf("kind" to JsonPrimitive("screen"), "fingerprint" to JsonPrimitive(it.fingerprint),
            "platformAppVersion" to JsonPrimitive("8.0"), "hashDomain" to JsonPrimitive(1), "filterRev" to JsonPrimitive(1),
            "layout" to JsonPrimitive(SlotWalk.layout(it))))
    }

    private fun body(vararg fields: Pair<String, Any>): String = JsonObject(fields.associate { (name, value) ->
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonObject -> value
            is JsonArray -> value
            else -> error("unsupported")
        }
    }).toString()

    private fun slots(vararg entries: Map<String, String>) = JsonArray(entries.map { entry -> JsonObject(entry.mapValues { JsonPrimitive(it.value) }) })

    private fun batch(id: String): String = JsonObject(mapOf("batchId" to JsonPrimitive(id),
        "items" to JsonArray(listOf(Json.parseToJsonElement(SkeletonSchema.serialize(item())))))).toString()

    private fun age(id: String) = sql { it.update("UPDATE installs SET created_day = ? WHERE install_id = ?", day.minusDays(40), UUID.fromString(id)) }

    private fun nextCode(): String {
        instant = instant.plusSeconds(30)
        return Totp.code(totpSecret, instant.epochSecond)
    }

    private suspend fun HttpClient.ops(path: String, body: String? = null, code: String? = null): HttpResponse = request(path) {
        method = if (body == null) HttpMethod.Get else HttpMethod.Post
        header(HttpHeaders.Authorization, "Bearer $operatorToken")
        code?.let { header("X-Census-Totp", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private fun json(response: String) = Json.parseToJsonElement(response).jsonObject

    @Test fun `the contributor routes grant, refuse, review, gate words and quarantine, with counts-only logs`() = runBlocking {
        Database.connect(config()).use { db ->
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logs)
            try {
                testApplication {
                    application { module(opsConfig(), db, clock, policy = Policy()) }
                    val (id, key) = "7e57ab1e-0000-4000-8000-000000000081" to secret(81)
                    val (other, otherKey) = UUID.randomUUID().toString() to secret(82)
                    for ((install, secret) in listOf(id to key, other to otherKey)) assertEquals(HttpStatusCode.OK, client.enrol(install, secret).status)
                    suspend fun post(path: String, json: String, who: String = id, secret: String = key) = client.signed(clock, who, secret, HttpMethod.Post, path, json)

                    assertError(post("/v1/contributor/grant", body("eligible" to true)), 403, "install_too_new")
                    age(id)
                    assertError(post("/v1/contributor/grant", body("eligible" to false)), 403, "not_eligible")
                    assertError(post("/v1/contributor/grant", """{"eligible":true,"shifts":3}"""), 400, "bad_request")
                    val granted = post("/v1/contributor/grant", body("eligible" to true))
                    assertEquals(HttpStatusCode.OK, granted.status)
                    assertEquals("""{"status":"granted","grantedDay":"$day","expiresDay":"${day.plusDays(30)}"}""", granted.bodyAsText())
                    assertEquals(granted.bodyAsText(), client.signed(clock, id, key, HttpMethod.Get, "/v1/contributor/grant").bodyAsText())

                    // No release yet: classes are refused with a retry; another install has no grant.
                    val classesBody = body("variant" to variant(), "slots" to JsonArray(listOf(JsonPrimitive("root/0/text"))))
                    val pending = post("/v1/contributor/classes", classesBody)
                    assertError(pending, 503, "release_unavailable")
                    assertEquals("3600", pending.headers[HttpHeaders.RetryAfter])
                    assertError(post("/v1/contributor/classes", classesBody, other, otherKey), 403, "grant_required")
                    sql { connection -> connection.autoCommit = false; connection.buildRelease(Policy(), day); connection.commit() }
                    assertEquals("""{"releaseDay":"$day","slots":[]}""", post("/v1/contributor/classes", classesBody).bodyAsText())

                    // A review of a screen the install has not sent is stored empty; after the upload it lands.
                    val review = body("variant" to variant(), "confirmations" to slots(mapOf("slot" to "root/0/text", "hash" to hash)),
                        "roles" to slots(mapOf("slot" to "root/0/text", "role" to "deadline")))
                    assertEquals("""{"confirmations":0,"roles":0,"skipped":2}""", post("/v1/contributor/review", review).bodyAsText())
                    assertEquals(HttpStatusCode.OK, post("/v1/skeletons", batch("first")).status)
                    assertEquals("""{"confirmations":1,"roles":1,"skipped":0}""", post("/v1/contributor/review", review).bodyAsText())
                    assertError(post("/v1/contributor/review", body("variant" to variant(),
                        "roles" to slots(mapOf("slot" to "root/0/text", "role" to "customer_name")))), 400, "bad_request")

                    // A live grant never permits an envelope: that stays the trusted tier's.
                    assertError(post("/v1/envelopes", """{"batchId":"contributor","items":[]}"""), 403, "not_trusted")

                    // The gate is closed: the word is refused, and the gate query says so.
                    val gates = post("/v1/contributor/gates", body("variant" to variant(), "slots" to slots(mapOf("slot" to "root/0/text", "hash" to hash))))
                    assertEquals("""{"slots":[{"slot":"root/0/text","hash":"$hash","open":false}]}""", gates.bodyAsText())
                    val words = post("/v1/contributor/words", body("variant" to variant(),
                        "words" to slots(mapOf("slot" to "root/0/text", "hash" to hash, "word" to wordText))))
                    assertEquals("""{"words":[{"slot":"root/0/text","status":"gate_closed"}]}""", words.bodyAsText())
                    assertEquals(0L, count("contributor_words"))

                    // "Ask the developer": sealed, listed on the quarantine page only, its note from the fixed list.
                    val artifact = """{"skeleton":${SkeletonSchema.serialize(item())}}""".toByteArray()
                    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(artifact)
                    assertError(post("/v1/contributor/quarantine", body("artifact" to encoded, "note" to "free text")), 400, "bad_request")
                    val quarantined = post("/v1/contributor/quarantine", body("artifact" to encoded, "note" to "unsure_what_screen"))
                    // Sol S1: the sealed bytes are the canonical form of the validated artifact; this phone sent that form.
                    val canonical = """{"skeleton":${SkeletonSchema.serialize(item())},"confirmations":[],"roles":[],"words":[]}"""
                    assertEquals("""{"status":"quarantined","expiresDay":"${day.plusDays(3)}","artifactSha256":"${cloud.trotter.census.server.auth.sha256Hex(canonical.toByteArray())}"}""",
                        quarantined.bodyAsText())
                    assertError(post("/v1/contributor/quarantine", """{"artifact":"$encoded","artifact":"$encoded"}"""), 400, "bad_request")
                    val listed = Json.parseToJsonElement(client.ops("/ops/quarantine").bodyAsText()).jsonArray.single().jsonObject
                    val itemId = listed.getValue("id").jsonPrimitive.content
                    assertEquals("unsure_what_screen", listed.getValue("note").jsonPrimitive.content)
                    val downloaded = json(client.ops("/ops/quarantine/$itemId").bodyAsText())
                    assertTrue(Base64.getDecoder().decode(downloaded.getValue("artifact").jsonPrimitive.content).size > artifact.size)
                    assertTrue(client.ops("/ops/quarantine/view").bodyAsText().contains(itemId))
                    assertFalse(client.ops("/ops/").bodyAsText().contains(itemId))
                    assertEquals(HttpStatusCode.NoContent, client.ops("/ops/quarantine/$itemId/delete", "", nextCode()).status)

                    // The operator revokes the grant: contributor uploads and reads stop.
                    val grants = Json.parseToJsonElement(client.ops("/ops/contributors").bodyAsText()).jsonArray
                    val grantId = grants.single().jsonObject.getValue("grantId").jsonPrimitive.content
                    assertError(client.ops("/ops/contributors/$grantId/revoke", """{"revoked":true}"""), 401, "totp_required")
                    assertEquals(HttpStatusCode.NoContent, client.ops("/ops/contributors/$grantId/revoke", """{"revoked":true}""", nextCode()).status)
                    assertError(post("/v1/contributor/review", review), 403, "grant_required")
                    assertError(post("/v1/contributor/grant", body("eligible" to true)), 403, "grant_revoked")
                    assertEquals("""{"status":"revoked"}""", client.signed(clock, id, key, HttpMethod.Get, "/v1/contributor/grant").bodyAsText())
                    // Contributor status never permits an envelope: the install is not trusted.
                    assertEquals("false", json(client.signed(clock, id, key, HttpMethod.Get, "/v1/me").bodyAsText()).getValue("trusted").jsonPrimitive.content)
                }
                for (event in logs.list) {
                    for (secret in listOf("7e57ab1e", hash, wordText, testKeyingToken())) assertFalse(event.formattedMessage.contains(secret), event.formattedMessage)
                }
                assertTrue(logs.list.any { it.formattedMessage == "contributor review confirmations=1 roles=1 skipped=0" })
            } finally {
                (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logs)
                logs.stop()
            }
        }
    }

    @Test fun `without the operator's public key the quarantine refuses every item`() = runBlocking {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(opsConfig(withKey = false), db, clock, policy = Policy()) }
                val (id, key) = UUID.randomUUID().toString() to secret(83)
                assertEquals(HttpStatusCode.OK, client.enrol(id, key).status)
                age(id)
                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/contributor/grant", body("eligible" to true)).status)
                val encoded = Base64.getUrlEncoder().encodeToString("""{"skeleton":${SkeletonSchema.serialize(item())}}""".toByteArray())
                assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/contributor/quarantine", body("artifact" to encoded)), 503, "quarantine_unconfigured")
                assertEquals(0L, count("quarantine_items"))
                assertError(client.ops("/ops/quarantine"), 503, "quarantine_unconfigured")
            }
        }
    }

    private fun testKeyingToken() = cloud.trotter.census.server.testKeying.token(hash)
}
