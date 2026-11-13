package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #80 (Sol S2): a backup does not keep one upload's epoch-tagged rows together. pg_dump writes heap order, so the
 * backup pipeline (`deploy/compose/backup.sh`) passes the dump through `order-tagged-dump.sh`, which sorts every
 * tagged table's rows by tag. This runs the real pg_dump, the real script and a psql restore into a fresh database.
 */
class TaggedDumpOrderTest : LifecycleTestDatabase() {
    private val script = File("../deploy/compose/order-tagged-dump.sh").canonicalFile

    /** A screen with [width] text views, each a distinct value, so one upload writes many slot rows. */
    private fun upload(seed: Int, width: Int = 20): ItemVerdict.Accepted {
        val children = List(width) { UiSkeletonNodeDto(className = "android.widget.TextView",
            text = mapOf("text" to TextSlot("%016x".format(seed * 1000L + it), "words:2"))) }
        val root = UiSkeletonNodeDto(className = "android.widget.ListView", children = children)
        val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = day.toString(), root = root)
        return SkeletonValidator.validate(Json.parseToJsonElement(SkeletonSchema.serialize(item)), policy, day) as ItemVerdict.Accepted
    }

    private fun tags(): Set<String> = sql { connection -> connection.createStatement().use { statement ->
        statement.executeQuery("SELECT install_tag FROM slot_sightings").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    } }

    /** The install_tag column of one table's COPY block, in dump order. */
    private fun block(dump: String, table: String): List<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.startsWith("COPY public.$table (") }
        val column = lines[start].substringAfter("(").substringBefore(")").split(", ").indexOf("install_tag")
        return lines.drop(start + 1).takeWhile { it != "\\." }.map { it.split('\t')[column] }
    }

    private fun contiguous(order: List<String>, upload: Set<String>): Boolean {
        val positions = order.withIndex().filter { it.value in upload }.map { it.index }
        return positions.last() - positions.first() + 1 == positions.size
    }

    private fun order(dump: String): String {
        val process = ProcessBuilder("bash", script.path).start()
        process.outputStream.use { it.write(dump.toByteArray()) }
        val out = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), process.errorStream.readBytes().decodeToString())
        return out
    }

    @Test fun `a dump of interleaved uploads restores whole and keeps no upload's rows together`() = runBlocking {
        Database.connect(config()).use { db ->
            val (a, b) = install() to install()
            val uploads = mutableListOf<Set<String>>()
            for ((index, id) in listOf(a, b, a, b).withIndex()) {
                val before = tags()
                observe(db, id, listOf(upload(index, width = 20 + index)))
                uploads += tags() - before
            }
            // Each upload is its own screen (a different width), so all its rows are new: a presence and a value per slot.
            assertEquals(listOf(40, 42, 44, 46), uploads.map { it.size })

            val raw = postgres.execInContainer("pg_dump", "-U", postgres.username, "-n", "public", postgres.databaseName)
            assertEquals(0, raw.exitCode, raw.stderr)
            // The problem: in heap order every upload's rows form one run.
            assertTrue(uploads.all { contiguous(block(raw.stdout, "slot_sightings"), it) })

            val ordered = order(raw.stdout)
            val slots = block(ordered, "slot_sightings")
            assertEquals(slots.sorted(), slots)
            assertFalse(uploads.any { contiguous(slots, it) })
            for (table in listOf("token_sightings_v5", "cluster_sightings_v5")) block(ordered, table).let { assertEquals(it.sorted(), it, table) }
            // Only row order changed.
            assertEquals(raw.stdout.lines().sorted(), ordered.lines().sorted())

            // The ordered dump restores into a fresh database in one transaction, every row intact.
            val database = "restored_" + UUID.randomUUID().toString().take(8)
            assertEquals(0, postgres.execInContainer("createdb", "-U", postgres.username, database).exitCode)
            // When pg_dump -n public names the schema itself, the fresh database's own must go first.
            if (ordered.contains("CREATE SCHEMA public;")) {
                assertEquals(0, postgres.execInContainer("psql", "-X", "-U", postgres.username, "-d", database, "-c", "DROP SCHEMA public").exitCode)
            }
            postgres.copyFileToContainer(Transferable.of(ordered.toByteArray()), "/tmp/$database.sql")
            val restore = postgres.execInContainer("psql", "-X", "-U", postgres.username, "-d", database, "--single-transaction",
                "-v", "ON_ERROR_STOP=1", "-q", "-f", "/tmp/$database.sql")
            assertEquals(0, restore.exitCode, restore.stderr)
            fun content(url: String) = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                listOf("slot_sightings", "token_sightings_v5", "cluster_sightings_v5", "clusters", "installs").associateWith { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT row_to_json(t)::text FROM $table t").use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1)) }.sorted()
                        }
                    }
                }
            }
            val restored = content(postgres.jdbcUrl.replace("/${postgres.databaseName}?", "/$database?"))
            assertEquals(content(postgres.jdbcUrl), restored)
            assertEquals(172, restored.getValue("slot_sightings").size)
        }
    }
}
