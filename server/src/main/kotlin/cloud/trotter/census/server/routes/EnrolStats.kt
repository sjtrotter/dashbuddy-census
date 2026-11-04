package cloud.trotter.census.server.routes

import java.util.concurrent.atomic.AtomicLong

/** The closed set of enrol outcomes the operator can count. Nothing from the request becomes a key. */
enum class EnrolResult(val key: String) {
    ENROLLED("enrolled"),
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    INSTALL_EXISTS("install_exists"),
    REVOKED("revoked"),
}

/**
 * Process-local enrol outcome counters, like AlarmStats: no identities, no client strings, counts only.
 * A refused enrolment never creates an install, so these counts are the only server-side trace of it besides
 * the access log line.
 */
class EnrolStats {
    private val counts = EnrolResult.entries.associateWith { AtomicLong() }
    fun record(result: EnrolResult) { counts.getValue(result).incrementAndGet() }
    /** Every key, zero included, in declaration order. */
    fun snapshot(): Map<String, Long> = EnrolResult.entries.associate { it.key to counts.getValue(it).get() }
}
