package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.QuarantineCrypto
import cloud.trotter.census.server.QuarantineNote
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.atRest
import cloud.trotter.census.server.routes.QuarantineArtifact
import cloud.trotter.census.server.testKeying
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.sql.DriverManager
import kotlin.concurrent.thread
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** #81: "Ask the developer" items are sealed to the operator, invisible to every other view, gone after 72 hours. */
class QuarantineTest : LifecycleTestDatabase() {
    private val operator = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    private val operatorKey = QuarantineCrypto.publicKey(Base64.getEncoder().encodeToString(operator.public.encoded))

    @TempDir lateinit var temporary: Path

    private fun skeleton(text: String = "Pick up by"): JsonObject {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView",
            text = mapOf("text" to TextSlot(requireNotNull(CensusHash.of(text)), "words:3")))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", children = listOf(leaf))
        val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = day.toString(), root = root)
        return Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
    }

    private fun artifact(words: List<Triple<String, String, String>> = emptyList()): ByteArray = JsonObject(mapOf(
        "skeleton" to skeleton(),
        "roles" to JsonArray(listOf(JsonObject(mapOf("slot" to JsonPrimitive("root/0/text"), "role" to JsonPrimitive("deadline"))))),
        "words" to JsonArray(words.map { (slot, hash, word) ->
            JsonObject(mapOf("slot" to JsonPrimitive(slot), "hash" to JsonPrimitive(hash), "word" to JsonPrimitive(word)))
        }),
    )).toString().toByteArray()

    private fun store(db: Database) = QuarantineStore(db, clock, policy, operatorKey)

    private suspend fun granted(db: Database): UUID = install(age = 40).also { ContributorStore(db, clock, policy).requestGrant(it, key, true, day) }

    /** The operator's side of [QuarantineCrypto.seal], written out independently of the server's code. */
    private fun open(id: UUID, sealed: ByteArray): ByteArray {
        assertEquals(1, sealed[0].toInt())
        val ephemeralBytes = sealed.copyOfRange(1, 45)
        val ephemeral = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(ephemeralBytes))
        val shared = KeyAgreement.getInstance("X25519").run { init(operator.private); doPhase(ephemeral, true); generateSecret() }
        val label = "census.quarantine.v1:$id".toByteArray()
        val key = QuarantineCrypto.hkdf(shared, ephemeralBytes, label)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 45, 12))
            updateAAD(label)
            doFinal(sealed, 57, sealed.size - 57)
        }
    }

    @Test fun `a quarantined artifact is sealed, invisible to every view but the quarantine page, and gone after 72 hours`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = granted(db)
            val bytes = artifact()
            val parsed = requireNotNull(QuarantineArtifact.parse(bytes, testKeying, policy, day))
            val result = store(db).submit(id, key, day, parsed.canonical, QuarantineNote.MIGHT_CONTAIN_CUSTOMER, parsed.words)
            assertEquals(ContributorOutcome.Ok(QuarantineResult.Stored(day.plusDays(3))), result)
            val entry = store(db).list(day).single()
            assertEquals(QuarantineNote.MIGHT_CONTAIN_CUSTOMER, entry.note)
            val (_, sealed) = requireNotNull(store(db).sealed(entry.id, day))
            // The validated artifact in its canonical form, and stored only as ciphertext.
            assertArrayEquals(parsed.canonical, open(entry.id, sealed))
            val canonical = Json.parseToJsonElement(parsed.canonical.decodeToString()).jsonObject
            assertEquals(listOf("skeleton", "confirmations", "roles", "words"), canonical.keys.toList())
            assertEquals(Json.parseToJsonElement(bytes.decodeToString()).jsonObject["roles"], canonical["roles"])
            // A phone that sends the canonical form gets back exactly its bytes.
            assertArrayEquals(parsed.canonical, requireNotNull(QuarantineArtifact.parse(parsed.canonical, testKeying, policy, day)).canonical)
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("skeleton"))
            // Not clustered, counted or drafted from: nothing outside the quarantine table changed.
            for (table in listOf("clusters", "cluster_samples", "token_sightings_v5", "cluster_sightings_v5", "slot_sightings", "slot_roles", "vocabulary_v5")) {
                assertEquals(0L, count(table), table)
            }
            val ops = OpsStore(db, clock, policy)
            assertTrue(ops.clusterSummary().isEmpty())
            assertEquals(0L, ops.vocabularyQueueCount())
            assertFalse(ops.ledger().toString().contains(entry.id.toString()))
            // The item is the install's: erasure finds it through its epoch tag.
            assertEquals(1L, sql { it.installRows("quarantine_items", id, day) })

            // At most 72 hours: the read honours the expiry day before any sweep, and the sweep deletes it.
            val received = day
            instant = received.plusDays(2).atTime(23, 59).toInstant(ZoneOffset.UTC)
            assertEquals(1, store(db).list(day).size)
            instant = received.plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC)
            assertTrue(store(db).list(day).isEmpty())
            assertNull(store(db).sealed(entry.id, day))
            assertEquals(1L, count("quarantine_items"))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("quarantine_items"))
        }
    }

    @Test fun `a word whose gate is closed never reaches the quarantine, and a bad artifact is refused`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = granted(db)
            val hash = requireNotNull(CensusHash.of("Pick up by"))
            val bytes = artifact(listOf(Triple("root/0/text", hash, "Pick up by")))
            val parsed = requireNotNull(QuarantineArtifact.parse(bytes, testKeying, policy, day))
            assertEquals(ContributorOutcome.Ok(QuarantineResult.GateClosed), store(db).submit(id, key, day, bytes, null, parsed.words))
            assertEquals(0L, count("quarantine_items"))
            // A word that does not hash to its hash, screen text in the skeleton, or an unknown key: refused before storage.
            assertNull(QuarantineArtifact.parse(artifact(listOf(Triple("root/0/text", hash, "Pick up at"))), testKeying, policy, day))
            val plaintext = skeleton().toString().replace("\"kind\":\"words:3\"", "\"kind\":\"words:3\",\"text\":\"Ann\"")
            assertNull(QuarantineArtifact.parse("""{"skeleton":$plaintext}""".toByteArray(), testKeying, policy, day))
            assertNull(QuarantineArtifact.parse("""{"skeleton":${skeleton()},"note":"x"}""".toByteArray(), testKeying, policy, day))
            // No grant, no item.
            val other = install(age = 40)
            assertEquals(ContributorOutcome.NoGrant, store(db).submit(other, key, day, artifact(), null, emptyList()))
        }
    }

    @Test fun `a repeated key at any depth is refused, so ungated text cannot hide behind a validated value`() = runBlocking {
        // Sol S1: a parser keeps one of two "words" values; validation saw the empty one, the bytes held the other.
        val hash = requireNotNull(CensusHash.of("Pick up by"))
        val smuggled = """{"skeleton":${skeleton()},"words":[{"slot":"root/0/text","hash":"$hash","word":"Pick up by"}],"words":[]}"""
        assertNull(QuarantineArtifact.parse(smuggled.toByteArray(), testKeying, policy, day))
        val nested = """{"skeleton":${skeleton()},"roles":[{"slot":"root/0/text","role":"tip","r\u006fle":"deadline"}]}"""
        assertNull(QuarantineArtifact.parse(nested.toByteArray(), testKeying, policy, day))
        assertTrue(cloud.trotter.census.server.ingest.hasDuplicateKeys("""{"a":{"b":1,"c":[{"d":1},{"d":2}]},"e":"{\"a\":1,\"a\":2}"}""").not())
        assertTrue(cloud.trotter.census.server.ingest.hasDuplicateKeys("""{"a":[{"b":1,"b":2}]}"""))
    }

    @Test fun `a submission racing an erasure never leaves an item behind`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol S4: the submission is admitted, then an erasure takes the install row and deletes the install's
            // quarantine items (none yet) before the submission writes; the erasure deletes the grant only later.
            val id = granted(db)
            val store = store(db)
            store.afterAdmission = {
                val erasure = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                erasure.autoCommit = false
                erasure.select("SELECT 1 FROM installs WHERE install_id = ? FOR UPDATE", id) { true }
                erasure.update("UPDATE installs SET erased_through = now() WHERE install_id = ?", id)
                erasure.update("DELETE FROM quarantine_items")
                thread {
                    // Once the submission waits on the install row, the erasure finishes and commits.
                    var waited = false
                    repeat(200) {
                        if (!waited) waited = sql { it.select("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") { r -> r.getLong(1) }!! } > 0
                        if (!waited) Thread.sleep(25)
                    }
                    erasure.update("DELETE FROM contributor_grants WHERE install_id = ?", id)
                    erasure.commit()
                    erasure.close()
                }
            }
            assertEquals(ContributorOutcome.NoGrant, store.submit(id, key, day, artifact(), null, emptyList()))
            assertEquals(0L, count("quarantine_items"))
        }
    }

    @Test fun `approval and an erasure of the same install take their locks in one order`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol S7: the install's trusted envelope drafts the cluster the sanitized copy lands on, so erasing the
            // envelope locks that cluster; approval locks the item, then the cluster.
            val id = granted(db)
            val sanitized = SkeletonValidator.validate(skeleton("Order details"), policy, day) as ItemVerdict.Accepted
            val keyed = testKeying.atRest(sanitized)
            sql { connection ->
                connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'doordash', ?, ?)",
                    keyed.item.fingerprint, day, day)
                val envelope = connection.select("INSERT INTO trusted_envelopes (install_id, fingerprint, envelope, received_day, purge_after) VALUES (?, ?, '{}', ?, ?) RETURNING id",
                    id, keyed.item.fingerprint, day, day.plusDays(30)) { it.getLong(1) }!!
                connection.update("UPDATE clusters SET draft = jsonb_build_object('envelopeId', ?::text) WHERE fingerprint = ?", envelope.toString(), keyed.item.fingerprint)
            }
            val store = store(db)
            store.submit(id, key, day, artifact(), null, emptyList())
            val item = store.list(day).single().id
            var deletion: Thread? = null
            var failure: Throwable? = null
            store.afterItemLock = {
                deletion = thread { runCatching { runBlocking { InstallStore(db, clock).deleteData(id, key, day) } }.onFailure { failure = it } }
                // The erasure reaches the item approval holds and waits there, before it touches the cluster.
                var waited = false
                repeat(200) {
                    if (!waited) waited = sql { it.select("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") { r -> r.getLong(1) }!! } > 0
                    if (!waited) Thread.sleep(25)
                }
                assertTrue(waited)
            }
            assertTrue(store.approve(item, keyed, day))
            deletion!!.join()
            assertNull(failure, "no deadlock: both transactions commit")
            assertEquals(0L, count("quarantine_items"))
            assertEquals(1L, count("cluster_samples"))
            assertEquals(0L, count("trusted_envelopes"))
        }
    }

    @Test fun `the operator's offline tool opens what the server sealed`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = granted(db)
            val bytes = artifact()
            store(db).submit(id, key, day, bytes, null, emptyList())
            val entry = store(db).list(day).single()
            val (_, sealed) = requireNotNull(store(db).sealed(entry.id, day))
            val keyFile = temporary.resolve("operator.key")
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(operator.private.encoded))
            val itemFile = temporary.resolve("item.json")
            Files.writeString(itemFile, """{"id":"${entry.id}","expiresDay":"${entry.expiresDay}","artifact":"${Base64.getEncoder().encodeToString(sealed)}"}""")
            val opened = tool("open", keyFile.toString(), itemFile.toString())
            assertEquals(0, opened.first)
            assertArrayEquals(bytes, opened.second)

            // keygen writes a private key and prints the public key the server accepts.
            val generated = temporary.resolve("generated.key")
            val keygen = tool("keygen", generated.toString())
            assertEquals(0, keygen.first)
            assertNotNull(QuarantineCrypto.publicKey(String(keygen.second).trim()))
            assertTrue(Files.readString(generated).isNotBlank())
        }
    }

    @Test fun `the operator's approval turns a sanitized copy into a cluster sample and deletes the item`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = granted(db)
            store(db).submit(id, key, day, artifact(), null, emptyList())
            val entry = store(db).list(day).single()
            val sanitized = SkeletonValidator.validate(skeleton("Order details"), policy, day) as ItemVerdict.Accepted
            assertTrue(store(db).approve(entry.id, testKeying.atRest(sanitized), day))
            assertEquals(0L, count("quarantine_items"))
            assertEquals(1L, count("cluster_samples"))
            // A sample with no sighting behind it: nothing is counted.
            assertEquals(0L, count("cluster_sightings_v5"))
            assertFalse(store(db).approve(entry.id, testKeying.atRest(sanitized), day))
        }
    }

    @Test fun `no backup holds a quarantine row or the day's slot release`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = granted(db)
            store(db).submit(id, key, day, artifact(), null, emptyList())
            sql { it.update("INSERT INTO slot_release_days (release_day) VALUES (?)", day) }
            assertEquals(1L, count("quarantine_items"))
            val script = File("../deploy/compose/backup.sh").readText()
            val flags = Regex("--exclude-table-data=[a-z_]+").findAll(script).map { it.value }.toList()
            assertEquals(setOf("quarantine_items", "slot_release", "slot_release_days"), flags.map { it.substringAfter('=') }.toSet())
            val dump = postgres.execInContainer(*(listOf("pg_dump", "-U", postgres.username) + flags + postgres.databaseName).toTypedArray())
            assertEquals(0, dump.exitCode, dump.stderr)
            assertTrue(dump.stdout.contains("CREATE TABLE public.quarantine_items"))
            for (table in listOf("quarantine_items", "slot_release", "slot_release_days")) assertFalse(dump.stdout.contains("COPY public.$table "), table)
            assertTrue(dump.stdout.contains("COPY public.contributor_grants "))
        }
    }

    private fun tool(vararg args: String): Pair<Int, ByteArray> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, File("../deploy/tools/QuarantineKey.java").canonicalPath) + args).start()
        val out = process.inputStream.readBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to out
    }
}
