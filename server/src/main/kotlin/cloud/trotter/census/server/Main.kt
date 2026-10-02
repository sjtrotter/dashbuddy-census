package cloud.trotter.census.server

import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.jobs.AlarmSink
import cloud.trotter.census.server.jobs.AlarmStats
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.jobs.LoggingAlarmSink
import cloud.trotter.census.server.jobs.PurgeJob
import cloud.trotter.census.server.jobs.SnsAlarmSink
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
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
            val stats = AlarmStats()
            val sink: AlarmSink = config.alertsTopicArn?.let { arn ->
                // Resolve the default credential/region chain only on the IO consumer, so even
                // SDK initialization failure remains an optional delivery failure.
                val client = lazy { SnsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build() }
                SnsAlarmSink(arn, { subject, message ->
                    client.value.publish(PublishRequest.builder().topicArn(arn).subject(subject).message(message).build())
                }).also { sns ->
                    sns.start(this, stats).invokeOnCompletion {
                        if (client.isInitialized()) runCatching { client.value.close() }
                    }
                }
            } ?: LoggingAlarmSink()
            val alarms = HealthAlarms(HealthStore(db, SystemClock), SystemClock, sink, stats, startedAt = SystemClock.now())
            module(config, db, alarmEvaluator = alarms)
            launch {
                val purge = PurgeJob(InstallStore(db, SystemClock), SystemClock, alarms = alarms)
                delay(1.minutes)
                while (true) {
                    try {
                        val counts = purge.runOnce()
                        log.info(
                            "purge nonces={} ledger_rows={} trusted_envelopes={} health_rows={}",
                            counts.nonces, counts.ledgerRows, counts.trustedEnvelopes, counts.healthRows,
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
}
