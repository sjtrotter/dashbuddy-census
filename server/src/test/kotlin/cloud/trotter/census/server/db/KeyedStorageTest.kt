package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** #85 acceptance: a stolen database or backup, without the pepper, cannot be guessed against. */
class KeyedStorageTest : LifecycleTestDatabase() {
    private val screenWords = listOf("Ready now", "Elm Street", "Corner Market", "Jordan")
    private val notificationWord = "Order ready"
    // #78: a v2 notification's action label is a slot hash like any other, so it must be keyed the same way.
    private val actionWord = "Accept order"
    private val candidates = screenWords + notificationWord + actionWord

    private fun screen(): ItemVerdict.Accepted {
        val children = screenWords.drop(1).map { word ->
            UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot(CensusHash.of(word), "words:2")))
        }
        val root = UiSkeletonNodeDto(className = "android.widget.LinearLayout", children = children)
        val fields = notificationFixture(day) - setOf("kind", "channelId", "slots")
        val raw = JsonObject(fields + mapOf(
            "schemaId" to JsonPrimitive(SkeletonSchema.SCHEMA_ID),
            "fingerprint" to JsonPrimitive(CensusFingerprint.of(root)),
            "windowTitle" to SkeletonSchema.json.encodeToJsonElement(TextSlot.serializer(), TextSlot(CensusHash.of(screenWords.first()), "words:2")),
            "root" to SkeletonSchema.json.encodeToJsonElement(UiSkeletonNodeDto.serializer(), root),
        ))
        return SkeletonValidator.validate(raw, policy, day) as ItemVerdict.Accepted
    }

    /**
     * The app's batch id at the pinned contract (`CensusApi.batchId`, core/network CensusApi.kt:288): sha256 of the
     * batch's sorted fingerprints joined by newlines, first 32 hex. Reproduced here; the server never derives it.
     */
    private fun appBatchId(fingerprints: List<String>): String =
        cloud.trotter.census.server.auth.sha256Hex(fingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8)).take(32)

    private fun notification(): ItemVerdict.Accepted = SkeletonValidator.validate(
        notificationV2Fixture(
            day, hash = requireNotNull(CensusHash.of(notificationWord)),
            actions = listOf(TextSlot(CensusHash.of(actionWord), "words:2"), TextSlot.WITHHELD),
        ),
        policy, day,
    ) as ItemVerdict.Accepted

    @Test fun `a dump without the pepper matches none of the candidate words the live server matches`() = runBlocking {
        Database.connect(config()).use { db ->
            val items = listOf(screen(), notification())
            repeat(policy.k) { observe(db, install(), items) }
            // A single-notification batch named the way the app names it: its id is derived from a public fingerprint.
            val publicFingerprint = notification().item.fingerprint
            val batchId = appBatchId(listOf(publicFingerprint))
            assertTrue(SkeletonStore(db, clock).ingest(install(), key, day, listOf(notification()), 0, emptyMap(), batchId, 1, BudgetPolicy()) is IngestOutcome.Stored)
            val ops = OpsStore(db, clock, policy)

            // The live server, holding the pepper, matches every candidate: hash, key, compare (the resolve route's path).
            val queue = ops.vocabularyQueue().map { it.jsonObject.getValue("tokenHash").jsonPrimitive.content }.toSet()
            val live = config().hashKeying
            assertEquals(candidates.size, queue.size)
            for (word in candidates) assertTrue(requireNotNull(live.candidate(word)) in queue, word)
            assertTrue(ops.resolve(requireNotNull(live.candidate("Ready now")), "Ready now", "corpus", false))

            // A thief with the dump hashes the same guesses publicly, or under a wrong pepper, and matches nothing.
            val dump = sql { it.dumpRows() }
            val publicGuesses = candidates.map { requireNotNull(CensusHash.of(it)) }
            assertEquals(emptySet<String>(), matches(dump, publicGuesses))
            // The guessed notification's public fingerprint, and the batch id the app derives from it, match nothing.
            assertEquals(emptySet<String>(), matches(dump, listOf(publicFingerprint, batchId)))
            assertEquals(setOf(testKeying.batch(batchId)), matches(dump, listOf(testKeying.batch(batchId))))
            val wrongPepper = HashKeying.fromHex("5".repeat(64))
            assertEquals(emptySet<String>(), matches(dump, candidates.map { requireNotNull(wrongPepper.candidate(it)) }))
            // Positive control: the dump does hold the keyed values (and the reviewed clear text the operator chose to store).
            assertEquals(queue, matches(dump, queue))
        }
    }

    @Test fun `ingest stores no raw census hash or public notification fingerprint in any column`() = runBlocking {
        Database.connect(config()).use { db ->
            val screen = screen(); val notification = notification()
            val id = install()
            observe(db, id, listOf(screen, notification))
            val raw = (screen.tokens + notification.tokens).map { it.hash }.toSet()
            assertEquals(candidates.size, raw.size)
            val dump = sql { it.dumpRows() }
            assertEquals(emptySet<String>(), matches(dump, raw + notification.item.fingerprint))

            // Notification fingerprints are keyed in the catalogue and in every reference to it; screen ones cover no text.
            val keyed = stored(notification).item.fingerprint
            for (table in listOf("clusters", "cluster_samples", "cluster_sightings_v5")) {
                assertEquals(setOf(screen.item.fingerprint, keyed), sql { it.select("SELECT jsonb_agg(DISTINCT fingerprint)::text FROM $table") { r ->
                    Json.parseToJsonElement(r.getString(1)).jsonArray.map { e -> e.jsonPrimitive.content }.toSet()
                } }, table)
            }
            val body = sql { it.select("SELECT skeleton->>'fingerprint' FROM cluster_samples WHERE fingerprint = ?", keyed) { r -> r.getString(1) } }
            assertEquals(keyed, body)
            assertEquals(raw.map { testKeying.token(it) }.toSet(), sql { it.select("SELECT jsonb_agg(token_hash)::text FROM token_sightings_v5") { r ->
                Json.parseToJsonElement(r.getString(1)).jsonArray.map { e -> e.jsonPrimitive.content }.toSet()
            } })

            // Equality survives keying: the same notification from a second install joins the same cluster.
            observe(db, install(), listOf(notification()))
            assertEquals(2L, sql { it.select("SELECT count(DISTINCT install_tag) FROM cluster_sightings_v5 WHERE fingerprint = ?", keyed) { r -> r.getLong(1) } })
            assertEquals(2L, count("clusters"))
        }
    }
}
