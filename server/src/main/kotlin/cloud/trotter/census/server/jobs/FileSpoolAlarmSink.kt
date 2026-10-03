package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** WARN is the system of record; the host publishes these bounded, best-effort copies. */
class FileSpoolAlarmSink(
    private val dir: Path,
    private val fallback: AlarmSink = LoggingAlarmSink(),
    private val maxFiles: Int = 64,
    private val clock: Clock,
) : AlarmSink {
    init {
        require(maxFiles > 0) { "Alarm spool capacity must be positive" }
    }

    val stats = AlarmStats()
    private val log = LoggerFactory.getLogger("Alarm")
    private var lastWarning: Instant? = null

    @Synchronized
    override fun raise(alarm: Alarm) {
        try {
            fallback.raise(alarm)
            Files.createDirectories(dir)
            val pending = Files.newDirectoryStream(dir, "*.alarm").use { entries ->
                entries.toList().sortedWith(
                    compareBy<Path> { it.fileName.toString().substringBefore('-').toLongOrNull() ?: Long.MIN_VALUE }
                        .thenBy { it.fileName.toString().substringAfter('-').removeSuffix(".alarm").toLongOrNull() ?: 0L },
                )
            }
            for (oldest in pending.take((pending.size - maxFiles + 1).coerceAtLeast(0))) {
                Files.deleteIfExists(oldest)
            }
            val name = "${clock.now().toEpochMilli()}-${counter.incrementAndGet()}.alarm"
            val temporary = dir.resolve("$name.tmp")
            try {
                Files.writeString(temporary, renderAlarm(alarm), CREATE_NEW)
                Files.move(temporary, dir.resolve(name), ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: IOException) {
            failed(failure)
        } catch (failure: DirectoryIteratorException) {
            failed(failure.cause ?: IOException(failure.javaClass.simpleName))
        }
    }

    private fun failed(failure: IOException) {
        stats.record("spool_failed")
        val now = clock.now()
        val previous = lastWarning
        if (previous == null || Duration.between(previous, now) >= Duration.ofMinutes(10)) {
            lastWarning = now
            log.warn("alarm_spool_failed class={}", failure.javaClass.simpleName)
        }
    }

    private companion object {
        val counter = AtomicLong()
    }
}
