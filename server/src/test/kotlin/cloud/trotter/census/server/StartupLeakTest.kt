package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises fatal startup in a separate JVM so exitProcess and all logging are observed (#1157 S1). */
class StartupLeakTest {
    @Test
    fun `database startup failure exits without leaking connection details`() {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            requireNotNull(System.getProperty("census.testClasspath")),
            "cloud.trotter.census.server.MainKt",
        ).apply {
            redirectErrorStream(true)
            environment().putAll(testEnvironment())
            environment()["DATABASE_URL"] = "jdbc:postgresql://127.0.0.1:1/census?password=PRIVATE_URL_PASSWORD_SENTINEL"
            environment()["DATABASE_USER"] = "PRIVATE_USER_SENTINEL"
            environment()["DATABASE_PASSWORD"] = "PRIVATE_PASSWORD_SENTINEL"
        }.start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            // Drain both streams while waiting, so a full pipe cannot stall startup.
            val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Startup did not exit within 60 seconds")
            assertEquals(1, process.exitValue())
            val captured = output.get(5, TimeUnit.SECONDS)
            listOf(
                "PRIVATE_URL_PASSWORD_SENTINEL", "PRIVATE_USER_SENTINEL", "PRIVATE_PASSWORD_SENTINEL", "127.0.0.1:1",
            ).forEach { assertFalse(captured.contains(it), "Startup output exposed a connection detail") }
            assertTrue(captured.contains("database startup failed ("), "Expected the sanitized database failure")
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            reader.shutdownNow()
        }
    }
}
