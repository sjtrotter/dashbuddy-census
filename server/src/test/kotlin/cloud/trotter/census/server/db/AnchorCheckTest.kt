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
import cloud.trotter.census.server.AnchorCheckPolicy
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.assertError
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.module
import cloud.trotter.census.server.ops.Totp
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

/**
 * #82: the anchor check. Every screen is one cluster: a button whose own text is `root/text` and a child text view
 * whose text is the slot asked about, `root/0/text`. Each app version is its own cluster variant, so one database
 * holds a constant slot (8.0), a slot below k (8.1), a regional one (8.2) and a varying one (8.3).
 */
class AnchorCheckTest : LifecycleTestDatabase() {
    private val chrome = TextSlot(requireNotNull(CensusHash.of("Order details")), "words:2")
    private val wordText = "Pick up by"
    private val word = TextSlot(requireNotNull(CensusHash.of(wordText)), "words:3")
    private val slot = "root/0/text"

    private fun slotOf(text: String): TextSlot = TextSlot(requireNotNull(CensusHash.of(text)), "words:${text.split(' ').size}")

    private fun screen(value: TextSlot, version: String): UiSkeletonDto {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to value))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to chrome), children = listOf(leaf))
        return UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = version, engineVersion = 1, day = day.toString(), root = root)
    }

    private fun accepted(value: TextSlot, version: String): ItemVerdict.Accepted = SkeletonValidator.validate(
        Json.parseToJsonElement(SkeletonSchema.serialize(screen(value, version))).jsonObject, policy, day,
    ) as ItemVerdict.Accepted

    private fun scope(version: String): SlotScope = screen(word, version).let {
        SlotScope(it.fingerprint, SlotVariant(version, 1, 1, SlotWalk.layout(it)))
    }

    /** [count] fresh installs (past quarantine) each upload the screen of [version] with [value] in the slot. */
    private suspend fun seen(db: Database, version: String, count: Int, value: (Int) -> TextSlot) {
        repeat(count) { n -> observe(db, install(age = 40), listOf(accepted(value(n), version))) }
    }

    private suspend fun constant(db: Database) = seen(db, "8.0", 11) { word }
    private suspend fun belowK(db: Database) = seen(db, "8.1", 3) { word }
    private suspend fun regional(db: Database) = seen(db, "8.2", 12) { if (it < 6) word else slotOf("North Plaza") }
    private suspend fun varying(db: Database) = seen(db, "8.3", 11) { if (it == 0) word else TextSlot("%016x".format(it + 0x5000L), "words:2") }

    private fun store(db: Database, limits: AnchorCheckPolicy = AnchorCheckPolicy()) = AnchorCheckStore(db, clock, policy.copy(anchorCheck = limits))
    private val operator = AnchorCaller.operator(AnchorCheckPolicy())

    private suspend fun AnchorCheckStore.ask(version: String, vararg candidates: String, caller: AnchorCaller = operator): AnchorOutcome =
        check(caller, scope(version), slot, candidates.toList())

    private fun answered(vararg matches: String?) = AnchorOutcome.Answered(matches.toList())

    @Test fun `a constant slot matches its own word in any case hasText accepts, and nothing else`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db)
            val store = store(db)
            assertEquals(answered(wordText), store.ask("8.0", wordText))
            // hasText compares case-insensitively; the census hash keeps case, so each case variant is compared.
            assertEquals(answered(wordText, wordText), store.ask("8.0", "pick up by", "PICK up by"))
            // Sol round 2, S1: two capitalized words in a row are never compared, so all caps and title case are refused.
            assertEquals(AnchorOutcome.Refused(listOf(0, 1)), store.ask("8.0", "PICK UP BY", "Pick Up By"))
            assertEquals(answered(null, null), store.ask("8.0", "Got it", "Pick up at"))
            // The chrome slot's own word is not the value slot's.
            assertEquals(answered(null), store.ask("8.0", "Order details"))
            // A contains-style fragment of the word is not a match: exact hasText only.
            assertEquals(answered(null), store.ask("8.0", "Pick up"))
        }
    }

    @Test fun `a slot constant on two capitalized words is never compared, whatever case the candidate takes`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol round 2, S1 and S4: nine Sybils and one victim can make a name constant; the check cannot reach it.
            seen(db, "8.4", 11) { slotOf("Riley Smith") }
            val store = store(db)
            assertEquals(answered(null, null, null), store.ask("8.4", "riley smith", "Riley smith", "RILEY smith"))
            assertEquals(AnchorOutcome.Refused(listOf(0, 1, 2)), store.ask("8.4", "Riley Smith", "RILEY SMITH", "riley Smith"))
            // Sol round 3, S1: a lower-case particle does not separate the capitals enough to be compared.
            seen(db, "8.7", 11) { slotOf("Maria de Souza") }
            assertEquals(answered(null, null), store.ask("8.7", "maria de souza", "Maria de souza"))
            assertEquals(AnchorOutcome.Refused(listOf(0, 1)), store.ask("8.7", "Maria de Souza", "maria de Souza"))
            seen(db, "8.5", 11) { slotOf("Accept Order") }
            assertEquals(AnchorOutcome.Refused(listOf(0)), store.ask("8.5", "Accept Order"))
            assertEquals(answered(null), store.ask("8.5", "Accept order"))
            seen(db, "8.6", 11) { slotOf("Accept order") }
            assertEquals(answered("Accept order"), store.ask("8.6", "accept order"))
        }
    }

    @Test fun `below k, regional and varying slots answer exactly like a constant slot that holds none of the candidates`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db); belowK(db); regional(db); varying(db)
            val readings = OpsStore(db, clock, policy).cluster(scope("8.0").fingerprint)!!.slots!!
            assertEquals(mapOf("8.0" to "constant", "8.1" to "unknown", "8.2" to "regional", "8.3" to "varying"),
                readings.associate { reading -> reading.variant.platformAppVersion to reading.slots.single { it.slot == slot }.slotClass.wire })

            fun recorded(version: String, candidate: String): Pair<AnchorOutcome, List<String>> {
                val statements = mutableListOf<String>()
                val store = store(db).apply { statementSeam = { statements += it } }
                return runBlocking { store.ask(version, candidate) } to statements
            }
            val (nonMatch, reference) = recorded("8.0", "Got it")
            assertEquals(answered(null), nonMatch)
            // The word each slot holds (on some installs) is asked about: none answers, and each runs the same statements.
            for (version in listOf("8.1", "8.2", "8.3")) {
                val (outcome, statements) = recorded(version, wordText)
                assertEquals(answered(null), outcome, version)
                assertEquals(reference, statements, version)
            }
            // A match runs the same statements too: the answer is the only difference.
            val (match, statements) = recorded("8.0", wordText)
            assertEquals(answered(wordText), match)
            assertEquals(reference, statements)
            assertEquals(4, reference.size, reference.toString())
            assertTrue(reference.any { "FROM slot_sightings" in it })
            // A cluster nobody uploaded answers the same way after the same statements.
            val unseen = mutableListOf<String>()
            val store = store(db).apply { statementSeam = { unseen += it } }
            assertEquals(answered(null), store.check(operator, SlotScope("f".repeat(64), scope("8.0").variant), slot, listOf(wordText)))
            assertEquals(reference, unseen)
            // The audit rows say match or no_answer, never the slot's class.
            assertEquals(setOf("match", "no_answer"), sql { it.select("SELECT string_agg(DISTINCT result, ',') FROM anchor_checks") { r -> r.getString(1) }!! }.split(',').toSet())
        }
    }

    @Test fun `a name-shaped candidate is refused before anything is hashed or read`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db)
            val statements = mutableListOf<String>()
            val store = store(db).apply { statementSeam = { statements += it } }
            assertEquals(AnchorOutcome.Refused(listOf(1, 2)), store.ask("8.0", wordText, "Brandon C", "brandon c"))
            assertTrue(statements.none { "slot_sightings" in it }, statements.toString())
            // The refused query is audited without any candidate text, and its candidates are spent.
            val row = store.auditLog(10).single()
            assertEquals("refused", row.result)
            assertEquals(3, row.candidates)
            assertEquals(3, sql { it.select("SELECT candidates FROM anchor_check_budget WHERE caller = 'operator'") { r -> r.getInt(1) } })
        }
    }

    @Test fun `each caller has a daily budget of candidates, refused ones included`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db)
            val limits = AnchorCheckPolicy(operatorDailyCandidates = 3)
            val store = store(db, limits)
            val caller = AnchorCaller.operator(limits)
            assertEquals(answered(wordText, null), store.ask("8.0", wordText, "Got it", caller = caller))
            val exhausted = store.ask("8.0", wordText, "Got it", caller = caller)
            assertTrue(exhausted is AnchorOutcome.BudgetExhausted)
            assertEquals(43_200L, (exhausted as AnchorOutcome.BudgetExhausted).retryAfterSeconds)
            assertEquals(AnchorOutcome.Refused(listOf(0)), store.ask("8.0", "Jane S.", caller = caller))
            assertTrue(store.ask("8.0", wordText, caller = caller) is AnchorOutcome.BudgetExhausted)
            // Another caller (the agent token's, #84) spends its own budget.
            assertEquals(answered(wordText), store.ask("8.0", wordText, caller = AnchorCaller("agent:test", 5)))
            assertEquals(listOf("agent:test" to "match", "operator" to "budget_exhausted", "operator" to "refused", "operator" to "budget_exhausted",
                "operator" to "match"), store.auditLog(10).map { it.caller to it.result })
            // A new UTC day starts a new budget.
            instant = instant.plusSeconds(86_400)
            assertEquals(answered(wordText), store.ask("8.0", wordText, caller = caller))
        }
    }

    @Test fun `the audit keeps no text and stops reading at its deadline`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db)
            val store = store(db)
            assertEquals(answered(wordText, null), store.ask("8.0", "pick up by", "  Got  it "))
            store.ask("8.0", "Got it", "riley smith")
            val (miss, match) = store.auditLog(10)
            assertEquals(AnchorAuditRow("operator", day.toString(), scope("8.0").fingerprint, scope("8.0").variant, slot, 2, "match"), match)
            assertEquals(AnchorAuditRow("operator", day.toString(), scope("8.0").fingerprint, scope("8.0").variant, slot, 2, "no_answer"), miss)
            // Sol rounds 1 and 2, S2: no column holds a candidate or the text a matched slot holds.
            val text = sql { it.select("SELECT string_agg(t::text, ' ') FROM anchor_checks t") { r -> r.getString(1) }!! }.lowercase()
            for (candidate in listOf("pick up by", "got it", "riley")) assertFalse(text.contains(candidate), text)

            // Audit rows go after 30 days, budget rows with the ingest ledger's 7.
            instant = instant.plusSeconds(8 * 86_400L)
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("anchor_check_budget"))
            assertEquals(2L, count("anchor_checks"))
            // S3: on its 30th day a row is no longer read, before any sweep removes it.
            instant = instant.plusSeconds(21 * 86_400L)
            assertEquals(2, store.auditLog(10).size)
            instant = instant.plusSeconds(86_400L)
            assertEquals(emptyList<AnchorAuditRow>(), store.auditLog(10))
            assertEquals(2L, count("anchor_checks"))
            assertEquals(2L, LifecycleStore(db, clock, policy).remainingDue().getValue("anchor_checks"))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("anchor_checks"))
        }
    }

    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

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

    private fun request(version: String, candidates: List<String>, fingerprint: String = scope(version).fingerprint): String = JsonObject(mapOf(
        "variant" to JsonObject(mapOf("fingerprint" to JsonPrimitive(fingerprint), "platformAppVersion" to JsonPrimitive(version),
            "hashDomain" to JsonPrimitive(1), "filterRev" to JsonPrimitive(1), "layout" to JsonPrimitive(scope(version).variant.layout))),
        "slot" to JsonPrimitive(slot),
        "candidates" to JsonArray(candidates.map(::JsonPrimitive)),
    )).toString()

    @Test fun `the operator route needs TOTP, answers per candidate, refuses names and logs counts only`() = runBlocking {
        Database.connect(config()).use { db ->
            constant(db); belowK(db)
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logs)
            try {
                testApplication {
                    application { module(config().copy(operatorTokenSha256 = hashSecret(operatorToken), operatorTotpSecret = totpSecret), db, clock, policy = policy) }
                    assertError(client.ops("/ops/anchor-check", request("8.0", listOf(wordText))), 401, "totp_required")
                    val answered = client.ops("/ops/anchor-check", request("8.0", listOf("pick up by", "Got it")), nextCode())
                    assertEquals(HttpStatusCode.OK, answered.status)
                    assertEquals("""{"results":[{"answer":"match","text":"Pick up by"},{"answer":"no_answer"}]}""", answered.bodyAsText())
                    // Below k: the same body as a non-match.
                    assertEquals("""{"results":[{"answer":"no_answer"}]}""", client.ops("/ops/anchor-check", request("8.1", listOf(wordText)), nextCode()).bodyAsText())
                    val refused = client.ops("/ops/anchor-check", request("8.0", listOf(wordText, "Riley M.")), nextCode())
                    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
                    assertEquals("""{"error":"not_chrome","refused":[1]}""", refused.bodyAsText())
                    // Bounds: 1 to 8 candidates of at most 128 characters, a well-formed variant and slot.
                    for (body in listOf(request("8.0", emptyList()), request("8.0", List(9) { "Got it" }), request("8.0", listOf("x".repeat(129))),
                        request("8.0", listOf("")), request("8.0", listOf(wordText), fingerprint = "F".repeat(64)),
                        request("8.0", listOf(wordText)).replace("\"slot\":\"$slot\"", "\"slot\":\"root/../text\""),
                        request("8.0", listOf(wordText)).replace("\"candidates\"", "\"extra\":1,\"candidates\""))) {
                        assertError(client.ops("/ops/anchor-check", body, nextCode()), 400, "bad_request")
                    }
                    val audit = Json.parseToJsonElement(client.ops("/ops/anchor-checks?limit=10").bodyAsText()).jsonArray
                    assertEquals(listOf("refused", "no_answer", "match"), audit.map { it.jsonObject.getValue("result").jsonPrimitive.content })
                    assertTrue(audit.all { it.jsonObject.keys == setOf("caller", "day", "fingerprint", "variant", "slot", "candidates", "result") })
                    assertError(client.ops("/ops/anchor-checks?limit=0"), 400, "bad_request")
                }
                val lines = logs.list.map { it.formattedMessage }
                for (line in lines) {
                    for (text in listOf(wordText, "pick up by", "Got it", "Riley", slot, scope("8.0").fingerprint)) assertFalse(line.contains(text), line)
                }
                assertTrue(lines.any { it == "anchor check candidates=2 matched=1" }, lines.toString())
                assertTrue(lines.any { it == "anchor check candidates=2 refused=1" }, lines.toString())
                assertTrue(lines.any { it.startsWith("ops method=POST path=/ops/anchor-check status=") }, lines.toString())
            } finally {
                (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logs)
                logs.stop()
            }
        }
    }
}
