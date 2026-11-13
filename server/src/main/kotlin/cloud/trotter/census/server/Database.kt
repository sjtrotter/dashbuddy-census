package cloud.trotter.census.server

import cloud.trotter.census.server.db.KeyedHashStorageMigration
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** PostgreSQL connections and startup migrations; no process-local durable state (#1157 S1). */
class Database private constructor(
    private val dataSource: HikariDataSource,
    internal val exposed: ExposedDatabase,
    /** #85: the pepper stored word hashes are keyed under; startup runs [migrate], which refuses a different one. */
    internal val keying: HashKeying,
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
            val flyway = flyway(config.databaseUrl, config.databaseUser, config.databasePassword, config.hashKeying)
                // Migrations connect before Hikari, so they need the same bounded socket reads.
                .jdbcProperties(mapOf("socketTimeout" to "30", "connectTimeout" to "10"))
                .load()
            flyway.migrate()
            flyway.configuration.dataSource.connection.use { KeyedHashStorageMigration.verify(it, config.hashKeying) }
        }

        /**
         * The one migration set: the SQL scripts plus V7, the Java migration that rewrites stored word hashes under
         * the pepper (#85). A Flyway built without V7 stops at V6 and refuses a database V7 has already migrated.
         */
        fun flyway(url: String, user: String, password: String, keying: HashKeying): FluentConfiguration =
            Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .javaMigrations(KeyedHashStorageMigration(keying))

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
                val exposed = ExposedDatabase.connect(dataSource)
                return Database(dataSource, exposed, config.hashKeying)
            } catch (failure: Exception) {
                dataSource.close()
                throw failure
            }
        }
    }
}
