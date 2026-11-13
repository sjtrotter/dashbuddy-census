package cloud.trotter.census.server

import java.sql.Connection

/**
 * #85: every row of every application table in [schema], each rendered as JSON text by PostgreSQL. This is what a
 * stolen dump or backup holds, every text-like column included, so a guess list can be matched against it.
 */
internal fun Connection.dumpRows(schema: String = "public"): List<String> {
    val tables = prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename <> 'flyway_schema_history'").use { statement ->
        statement.setString(1, schema)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    check(tables.isNotEmpty()) { "no tables in $schema" }
    return tables.flatMap { table ->
        createStatement().use { statement ->
            statement.executeQuery("SELECT row_to_json(t)::text FROM \"$schema\".\"$table\" t").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }
}

/** The values from [needles] that appear anywhere in [dump]. */
internal fun matches(dump: List<String>, needles: Collection<String>): Set<String> =
    needles.filter { needle -> dump.any { it.contains(needle) } }.toSet()
