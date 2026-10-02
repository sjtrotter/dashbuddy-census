package cloud.trotter.census.server.jobs

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.assertPrivateLogs
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SnsAlarmSinkTest {
    private val arn = "arn:aws:sns:us-east-2:000000000000:census-alerts"
    private val alarm = Alarm("silent_rule_death", "doordash", "8.0", "12345678", listOf("doordash.screen.offer"))

    @Test
    fun `fallback runs first and publisher receives exactly the validated fields`() = runBlocking {
        val fallback = AtomicInteger()
        val delivered = LinkedBlockingQueue<Triple<String, String, Int>>()
        val sink = SnsAlarmSink(arn, { subject, message ->
            delivered.add(Triple(subject, message, fallback.get()))
        }, AlarmSink { fallback.incrementAndGet() })
        val worker = sink.start(this, AlarmStats())
        try {
            sink.raise(alarm)
            assertEquals(
                Triple(
                    "census alarm: silent_rule_death",
                    "kind=silent_rule_death\nplatform=doordash\nversion=8.0\ninstall_prefix=12345678\nrule_ids=[doordash.screen.offer]",
                    1,
                ),
                delivered.poll(5, TimeUnit.SECONDS),
            )
        } finally {
            worker.cancelAndJoin()
        }
        // Cancellation/closed delivery queue must not suppress the system-of-record fallback.
        sink.raise(alarm)
        assertEquals(2, fallback.get())
    }

    @Test
    fun `hostile fields including version are redacted identically in log and SNS`() = runBlocking {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val delivered = LinkedBlockingQueue<Pair<String, String>>()
        val sink = SnsAlarmSink(arn, { subject, message -> delivered.add(subject to message) })
        val worker = sink.start(this, AlarmStats())
        try {
            sink.raise(alarm.copy(version = "PRIVATE_VERSION\nsecret=value"))
            val message = requireNotNull(delivered.poll(5, TimeUnit.SECONDS)).second
            assertEquals(
                "kind=silent_rule_death\nplatform=doordash\nversion=[redacted]\ninstall_prefix=12345678\nrule_ids=[doordash.screen.offer]",
                message,
            )
            assertEquals("alarm " + message.replace('\n', ' '), logs.list.single().formattedMessage)
            assertEquals(Level.WARN, logs.list.single().level)
            sink.raise(Alarm("PRIVATE_KIND", "PRIVATE_PLATFORM", "PRIVATE_VERSION", "PRIVATE_PREFIX", listOf("PRIVATE_RULE")))
            assertEquals(
                "census alarm: [redacted]" to "kind=[redacted]\nplatform=[redacted]\nversion=[redacted]\ninstall_prefix=[redacted]\nrule_ids=[[redacted]]",
                delivered.poll(5, TimeUnit.SECONDS),
            )
            assertPrivateLogs(logs.list, listOf("PRIVATE_", "secret=value", arn, "000000000000"))
            assertFalse(message.contains(arn))
        } finally {
            worker.cancelAndJoin()
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `blocked publisher never blocks caller and queue drops oldest beyond thirty two`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fallback = AtomicInteger()
        val delivered = LinkedBlockingQueue<String>()
        val sink = SnsAlarmSink(arn, { _, message ->
            if (message.contains("\nversion=0\n")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            delivered.add(message)
        }, AlarmSink { fallback.incrementAndGet() })
        val worker = sink.start(this, AlarmStats())
        try {
            sink.raise(alarm.copy(version = "0"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                for (version in 1..40) sink.raise(alarm.copy(version = version.toString()))
            }
            assertEquals(41, fallback.get())
            release.countDown()
            val versions = (0..32).map {
                requireNotNull(delivered.poll(5, TimeUnit.SECONDS)).lineSequence()
                    .first { line -> line.startsWith("version=") }.removePrefix("version=").toInt()
            }
            assertEquals(listOf(0) + (9..40).toList(), versions)
            assertTrue(delivered.isEmpty())
        } finally {
            release.countDown()
            worker.cancelAndJoin()
        }
    }

    @Test
    fun `throwing publisher is isolated counts every failure and warns once per ten minutes`() = runBlocking {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val fallback = AtomicInteger()
        val flushed = LinkedBlockingQueue<Unit>()
        val now = AtomicLong()
        val stats = AlarmStats()
        val sink = SnsAlarmSink(arn, { _, message ->
            if (message.contains("\nversion=0\n")) flushed.add(Unit)
            else throw IllegalStateException("PRIVATE_PUBLISH_FAILURE $arn payload=PRIVATE_PAYLOAD")
        }, AlarmSink { fallback.incrementAndGet() })
        val worker = sink.start(this, stats, now::get)
        fun flush() {
            sink.raise(alarm.copy(version = "0"))
            assertEquals(Unit, flushed.poll(5, TimeUnit.SECONDS))
        }
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { repeat(3) { sink.raise(alarm) } }
            flush()
            assertEquals(3L, stats.snapshot()["sns_failed"])
            assertEquals(1, logs.list.size)
            now.set(TimeUnit.MINUTES.toNanos(10) - 1)
            sink.raise(alarm)
            flush()
            assertEquals(1, logs.list.size)
            now.incrementAndGet()
            sink.raise(alarm)
            flush()
            assertEquals(5L, stats.snapshot()["sns_failed"])
            assertEquals(8, fallback.get())
            assertEquals(2, logs.list.size)
            logs.list.forEach {
                assertEquals("Alarm", it.loggerName)
                assertEquals(Level.WARN, it.level)
                assertEquals("sns_publish_failed class=IllegalStateException", it.formattedMessage)
                assertNull(it.throwableProxy)
            }
            assertPrivateLogs(logs.list, listOf("PRIVATE_", arn, "000000000000", "payload="))
            assertTrue(worker.isActive)
        } finally {
            worker.cancelAndJoin()
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `throwing fallback still permits best effort publishing`() = runBlocking {
        val delivered = LinkedBlockingQueue<String>()
        val sink = SnsAlarmSink(arn, { _, message -> delivered.add(message) }, AlarmSink { throw IllegalStateException() })
        val worker = sink.start(this, AlarmStats())
        try {
            sink.raise(alarm)
            assertEquals(renderAlarm(alarm), delivered.poll(5, TimeUnit.SECONDS))
        } finally {
            worker.cancelAndJoin()
        }
    }
}
