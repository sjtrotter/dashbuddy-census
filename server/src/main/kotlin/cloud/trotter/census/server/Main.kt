package cloud.trotter.census.server

import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.jobs.PurgeJob
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Starts migrations, the connection pool, and HTTP in that order (#1157 S1). */
fun main() {
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
        log.error(failure.message)
        exitProcess(1)
    }
    val database = try {
        Database.migrate(config)
        Database.connect(config)
    } catch (t: Throwable) {
        // The throwable is attached on purpose: SafeThrowableConverter renders ONLY the exception class chain
        // and the top frames (never a message), so the full diagnostic lineage survives without the JDBC URL.
        log.error("database startup failed ({}); check DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD", t.javaClass.simpleName, t)
        exitProcess(1)
    }
    database.use { db ->
        embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
            module(config, db)
            launch {
                val purge = PurgeJob(InstallStore(db, SystemClock), SystemClock)
                delay(1.minutes)
                while (true) {
                    try {
                        val counts = purge.runOnce()
                        log.info("purge nonces={} ledger_rows={}", counts.nonces, counts.ledgerRows)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        log.info("purge failures=1")
                    }
                    delay(6.hours)
                }
            }
        }.start(wait = true)
    }
}
