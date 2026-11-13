package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.LocalDate

/** What `/ops/pause` shows: the switch and the UTC day it last changed (null = never set). */
@Serializable
data class PauseState(val paused: Boolean, val changedDay: String? = null)

/**
 * #79: the upload pause switch. One operator action stops uploads from every non-trusted install without a
 * deploy; it lives in the database so a restart keeps it. Withdrawal and data deletion are never paused.
 */
class PauseStore(private val db: Database, private val clock: Clock) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun state(): PauseState = query {
        select("SELECT paused, changed_day FROM ingest_pause WHERE singleton") { row ->
            PauseState(row.getBoolean("paused"), row.getObject("changed_day", LocalDate::class.java).toString())
        } ?: PauseState(false)
    }

    suspend fun paused(): Boolean = state().paused

    suspend fun set(paused: Boolean): PauseState = query {
        val day = clock.today()
        update(
            """INSERT INTO ingest_pause (singleton, paused, changed_day) VALUES (true, ?, ?)
                ON CONFLICT (singleton) DO UPDATE SET paused = EXCLUDED.paused, changed_day = EXCLUDED.changed_day""",
            paused, day,
        )
        PauseState(paused, day.toString())
    }
}
