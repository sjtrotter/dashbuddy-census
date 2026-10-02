package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/** Exceptions can retain request bodies and SQL credentials in causes or suppressed failures.
 * Reject these events before any appender sees them; request status is logged separately.
 */
class PrivateThrowableFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any?>?,
        throwable: Throwable?,
    ): FilterReply = if (throwable != null || params?.any { it is Throwable } == true) FilterReply.DENY else FilterReply.NEUTRAL
}
