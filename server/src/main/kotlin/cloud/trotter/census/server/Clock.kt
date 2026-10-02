package cloud.trotter.census.server

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

fun Clock.today(): LocalDate = now().atOffset(ZoneOffset.UTC).toLocalDate()

/** Ceiling in whole seconds, so Retry-After never precedes the next UTC day. */
fun Clock.secondsToUtcMidnight(): Long {
    val current = now()
    val midnight = current.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    return midnight.epochSecond - current.epochSecond
}
