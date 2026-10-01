package cloud.trotter.census.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** PostgreSQL connections and startup migrations; no process-local durable state (#1157 S1). */
class Database private constructor(
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    fun checkReady(): Boolean = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.queryTimeout = 2
            statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
        }
    }

    override fun close() = dataSource.close()

    /** Performs migrations before opening the application's bounded pool (#1157 S1). */
    companion object {
        fun migrate(config: Config) {
            Flyway.configure()
                .dataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
                // Migrations connect before Hikari, so they need the same bounded socket reads.
                .jdbcProperties(mapOf("socketTimeout" to "30", "connectTimeout" to "10"))
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        fun connect(config: Config): Database {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.databaseUrl
                    username = config.databaseUser
                    password = config.databasePassword
                    maximumPoolSize = 5
                    minimumIdle = 1
                    addDataSourceProperty("socketTimeout", "30")
                    connectionTimeout = 10_000
                    initializationFailTimeout = 10_000
                    validationTimeout = 2_000
                    poolName = "census"
                },
            )
            try {
                ExposedDatabase.connect(dataSource)
                return Database(dataSource)
            } catch (failure: Exception) {
                dataSource.close()
                throw failure
            }
        }
    }
}
