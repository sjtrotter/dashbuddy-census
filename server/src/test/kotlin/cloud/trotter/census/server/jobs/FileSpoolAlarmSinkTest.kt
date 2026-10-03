package cloud.trotter.census.server.jobs

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.assertPrivateLogs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class FileSpoolAlarmSinkTest {
    @TempDir
    lateinit var dir: Path

    private val clock = MutableClock()
    private val alarm = Alarm("silent_rule_death", "doordash", "8.0", "12345678", listOf("doordash.screen.offer"))

    @Test
    fun `fallback runs first and atomic file contains exactly the rendered alarm`() {
        var fallbacks = 0
        val sink = FileSpoolAlarmSink(dir, AlarmSink {
            assertTrue(files().isEmpty())
            fallbacks++
        }, clock = clock)
        sink.raise(alarm)
        assertEquals(1, fallbacks)
        val file = files().single()
        assertTrue(file.fileName.toString().matches(Regex("${clock.now().toEpochMilli()}-[0-9]+\\.alarm")))
        assertEquals(renderAlarm(alarm), Files.readString(file))
        assertFalse(files().any { it.toString().endsWith(".tmp") })
        assertTrue(sink.stats.snapshot().isEmpty())
    }

    @Test
    fun `default cap drops oldest and numeric counter breaks ties within a millisecond`() {
        val sink = FileSpoolAlarmSink(dir, AlarmSink {}, clock = clock)
        repeat(65) { sink.raise(alarm.copy(version = it.toString())) }
        assertEquals(64, files().size)
        assertEquals((1..64).map { renderAlarm(alarm.copy(version = it.toString())) }.toSet(), files().map(Files::readString).toSet())
        assertFalse(files().any { it.toString().endsWith(".tmp") })
    }

    @Test
    fun `custom cap drops oldest across restarts and ignores unrelated files`() {
        val oldest = dir.resolve("1-1.alarm")
        val newer = dir.resolve("2-1.alarm")
        Files.writeString(oldest, "oldest")
        Files.writeString(newer, "newer")
        val unrelated = dir.resolve("keep.txt")
        Files.writeString(unrelated, "keep")
        FileSpoolAlarmSink(dir, AlarmSink {}, maxFiles = 2, clock = clock).raise(alarm)
        assertFalse(Files.exists(oldest))
        assertTrue(Files.exists(newer))
        assertTrue(Files.exists(unrelated))
        assertEquals(2, files().count { it.toString().endsWith(".alarm") })
    }

    @Test
    fun `unwritable directory counts failures and warns once per ten minutes without throwing`() {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val blocked = Files.createDirectory(dir.resolve("PRIVATE_SPOOL"))
        val permissions = Files.getPosixFilePermissions(blocked)
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("r-x------"))
        // Root can bypass mode bits; a regular-file parent forces the same I/O failure path.
        val unwritable = if (Files.isWritable(blocked)) {
            Files.writeString(dir.resolve("PRIVATE_PARENT"), "").resolve("spool")
        } else blocked
        val sink = FileSpoolAlarmSink(unwritable, clock = clock)
        try {
            repeat(3) { sink.raise(alarm) }
            assertEquals(3L, sink.stats.snapshot()["spool_failed"])
            assertEquals(1, logs.list.count { it.formattedMessage.startsWith("alarm_spool_failed ") })
            clock.instant = clock.instant.plusSeconds(599)
            sink.raise(alarm)
            assertEquals(1, logs.list.count { it.formattedMessage.startsWith("alarm_spool_failed ") })
            clock.instant = clock.instant.plusSeconds(1)
            sink.raise(alarm)
            assertEquals(5L, sink.stats.snapshot()["spool_failed"])
            val warnings = logs.list.filter { it.formattedMessage.startsWith("alarm_spool_failed ") }
            assertEquals(2, warnings.size)
            warnings.forEach {
                assertEquals(Level.WARN, it.level)
                assertTrue(it.formattedMessage.matches(Regex("alarm_spool_failed class=[A-Za-z]+")))
                assertNull(it.throwableProxy)
            }
            assertEquals(5, logs.list.count { it.formattedMessage == "alarm " + renderAlarm(alarm).replace('\n', ' ') })
            assertTrue(logs.list.first().formattedMessage.startsWith("alarm "))
            assertPrivateLogs(logs.list, listOf("PRIVATE_", dir.toString()))
        } finally {
            Files.setPosixFilePermissions(blocked, permissions)
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `hostile version is redacted identically in spool and WARN`() {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val hostile = alarm.copy(version = "PRIVATE_VERSION\nsecret=value")
            FileSpoolAlarmSink(dir, clock = clock).raise(hostile)
            val message = Files.readString(files().single())
            assertEquals(renderAlarm(hostile), message)
            assertTrue(message.contains("\nversion=[redacted]\n"))
            assertFalse(message.contains("PRIVATE_"))
            assertFalse(message.contains("secret=value"))
            assertEquals("alarm " + message.replace('\n', ' '), logs.list.single().formattedMessage)
            assertEquals(Level.WARN, logs.list.single().level)
            assertPrivateLogs(logs.list, listOf("PRIVATE_", "secret=value"))
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    private fun files(): List<Path> = Files.newDirectoryStream(dir).use { it.toList() }

    private class MutableClock(var instant: Instant = Instant.parse("2026-10-02T12:00:00Z")) : Clock {
        override fun now(): Instant = instant
    }
}
