package cloud.trotter.census.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlin.system.exitProcess

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
        log.error("database startup failed ({}); check DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD", t.javaClass.simpleName)
        exitProcess(1)
    }
    database.use { db ->
        embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
            module(config, db)
        }.start(wait = true)
    }
}
