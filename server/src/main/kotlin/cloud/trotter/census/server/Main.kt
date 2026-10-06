package cloud.trotter.census.server

import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.jobs.AlarmSink
import cloud.trotter.census.server.jobs.AlarmStats
import cloud.trotter.census.server.jobs.FileSpoolAlarmSink
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.jobs.LoggingAlarmSink
import cloud.trotter.census.server.jobs.PurgeJob
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Migrations and withdrawal replay must succeed before HTTP can start. */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && (args.size != 2 || args[0] != "--reapply-withdrawals" || args[1].startsWith("--"))) {
        System.err.println("Usage: census [--reapply-withdrawals <csv>]")
        exitProcess(2)
    }
    val journal = args.getOrNull(1)
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    val log = LoggerFactory.getLogger("census.startup")
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        log.error("uncaught exception in thread {} ({})", thread.name, failure.javaClass.simpleName)
        exitProcess(1)
    }
    val config = try {
        Config.fromEnv()
    } catch (failure: IllegalArgumentException) {
        // Config messages name the missing VARIABLE, never a value — safe on both paths.
        fail(journal, log, "configuration", failure, detail = failure.message)
    }
    val database = try {
        Database.migrate(config)
        Database.connect(config)
    } catch (t: Throwable) {
        if (journal != null) fail(journal, log, "database", t)
        // The throwable is attached on purpose: SafeThrowableConverter renders ONLY the exception class chain
        // and the top frames (never a message), so the full diagnostic lineage survives without the JDBC URL.
        log.error("database startup failed ({}); check DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD", t.javaClass.simpleName, t)
        exitProcess(1)
    }
    database.use { db ->
        try {
            val store = InstallStore(db, SystemClock)
            val merged = if (journal == null) 0 else {
                val rows = Files.newBufferedReader(Path.of(journal)).use(::readWithdrawalJournal)
                runBlocking { store.mergeWithdrawalJournal(rows) }
            }
            val replay = runBlocking { store.reapplyWithdrawals() }
            if (journal != null) {
                println("withdrawal replay merged=$merged matched=${replay.matched} rows=${replay.deletedRows.values.sum()} kept_reenrolled=${replay.keptReenrolled}")
            } else {
                log.info("withdrawal replay matched={} rows={} kept_reenrolled={}", replay.matched, replay.deletedRows, replay.keptReenrolled)
            }
            if (replay.matched > 0) log.warn("withdrawal replay deleted {} resurrected install(s) — a restore reintroduced withdrawn data", replay.matched)
        } catch (t: Throwable) {
            fail(journal, log, "withdrawal replay", t)
        }
        if (journal != null) return@use
        embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
            val spool = config.alarmSpoolDir?.let { FileSpoolAlarmSink(Path.of(it), clock = SystemClock) }
            val sink: AlarmSink = spool ?: LoggingAlarmSink()
            val stats = spool?.stats ?: AlarmStats()
            val alarms = HealthAlarms(HealthStore(db, SystemClock), SystemClock, sink, stats, startedAt = SystemClock.now())
            module(config, db, alarmEvaluator = alarms)
            launch {
                val purge = PurgeJob(InstallStore(db, SystemClock), SystemClock, alarms = alarms)
                delay(1.minutes)
                while (true) {
                    try {
                        val counts = purge.runOnce()
                        log.info(
                            "purge nonces={} ledger_rows={} trusted_envelopes={} health_rows={} withdrawals={}",
                            counts.nonces, counts.ledgerRows, counts.trustedEnvelopes, counts.healthRows, counts.withdrawals,
                        )
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        log.info("purge failures=1")
                    }
                    delay(6.hours)
                }
            }
        }.start(wait = true)
    }
    if (journal != null) exitProcess(0)
}

/** COPY CSV has two scalar fields; PostgreSQL emits timestamps with a space and offsets such as +00. */
internal fun readWithdrawalJournal(reader: Reader): List<Pair<String, Instant>> =
    reader.buffered().lineSequence().filter { it.isNotBlank() }.mapIndexedNotNull { index, line ->
        val fields = line.split(',').map { field ->
            val value = field.removeSurrounding("\"")
            require('"' !in value) { "Invalid withdrawal journal CSV" }
            value
        }
        require(fields.size == 2) { "Invalid withdrawal journal CSV" }
        if (index == 0 && fields == listOf("install_id_hash", "withdrawn_at")) null
        else {
            val timestamp = fields[1].replace(' ', 'T').replace(journalHourOffset, "$1:00")
            fields[0] to OffsetDateTime.parse(timestamp).toInstant()
        }
    }.toList()

private val journalHourOffset = Regex("([+-]\\d{2})$")

/**
 * #1192: the ONE failure reporter for both modes. The one-shot CLI prints to stderr for the operator (class name,
 * plus a [detail] that is known to be value-free — a Config message names the variable, never its value); the
 * server mode logs through SLF4J (the throwable is attached only there — SafeThrowableConverter renders the class
 * chain, never a message).
 */
private fun fail(journal: String?, log: org.slf4j.Logger, stage: String, failure: Throwable, detail: String? = null): Nothing {
    if (journal != null) {
        val where = if (stage == "withdrawal replay") "" else " at $stage"
        System.err.println("withdrawal replay failed$where (${failure.javaClass.simpleName})" + (detail?.let { ": $it" } ?: ""))
    } else {
        log.error("$stage failed ({})" + (detail?.let { ": $it" } ?: ""), failure.javaClass.simpleName, failure)
    }
    exitProcess(1)
}
