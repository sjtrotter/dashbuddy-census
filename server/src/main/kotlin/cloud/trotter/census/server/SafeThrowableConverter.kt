package cloud.trotter.census.server

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.IThrowableProxy
import java.util.Collections
import java.util.IdentityHashMap

/** Exception diagnostics contain class names and outer frames only, never messages or suppressed data. */
class SafeThrowableConverter : ThrowableProxyConverter() {
    override fun throwableProxyToString(throwable: IThrowableProxy): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<IThrowableProxy, Boolean>())
        var current: IThrowableProxy? = throwable
        while (current != null && seen.add(current)) {
            if (isNotEmpty()) append(" <- ")
            append(current.className)
            current = current.cause
        }
        append('\n')
        throwable.stackTraceElementProxyArray?.take(3)?.forEach { proxy ->
            val frame = proxy.stackTraceElement
            append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber)
            append('\n')
        }
    }
}
