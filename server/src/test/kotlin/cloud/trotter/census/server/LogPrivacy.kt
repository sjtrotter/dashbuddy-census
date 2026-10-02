package cloud.trotter.census.server

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import org.junit.jupiter.api.Assertions.assertFalse
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

internal fun assertPrivateLogs(events: List<ILoggingEvent>, forbidden: List<String>) {
    val layout = PatternLayout().apply {
        context = LoggerFactory.getILoggerFactory() as LoggerContext
        // Render even nested exceptions, independently of production's defensive %nopex.
        pattern = "%d{yyyy-MM-dd,UTC} %-5level %logger{36} - %msg%n%ex{full}"
        start()
    }
    try {
        events.forEach { event ->
            val seen = Collections.newSetFromMap(IdentityHashMap<IThrowableProxy, Boolean>())
            fun inspect(failure: IThrowableProxy?) {
                if (failure == null || !seen.add(failure)) return
                forbidden.forEach { assertFalse(failure.message.orEmpty().contains(it), "Throwable disclosed private data") }
                inspect(failure.cause)
                failure.suppressed?.forEach(::inspect)
            }
            inspect(event.throwableProxy)
            val rendered = layout.doLayout(event)
            forbidden.forEach {
                assertFalse(event.formattedMessage.contains(it), "Message disclosed private data")
                assertFalse(rendered.contains(it), "Rendered log disclosed private data")
            }
        }
    } finally {
        layout.stop()
    }
}
