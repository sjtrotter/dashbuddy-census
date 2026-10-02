package cloud.trotter.census.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import org.junit.jupiter.api.Assertions.assertFalse
import org.slf4j.LoggerFactory

/** Use the production encoder, including its registered safe throwable converter. */
internal fun renderLog(event: ILoggingEvent): String {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDOUT") as OutputStreamAppender<*>
    val encoder = appender.encoder as PatternLayoutEncoder
    return encoder.layout.doLayout(event)
}

internal fun assertPrivateLogs(events: List<ILoggingEvent>, forbidden: List<String>) {
    events.forEach { event ->
        val rendered = renderLog(event)
        forbidden.forEach {
            assertFalse(event.formattedMessage.contains(it), "Message disclosed private data")
            assertFalse(rendered.contains(it), "Rendered log disclosed private data")
        }
    }
}
