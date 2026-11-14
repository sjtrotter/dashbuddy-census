package cloud.trotter.census.server.db

import java.sql.Connection

/**
 * Test seam (#81, #82): this connection, reporting the SQL of every statement it prepares to [seam], so a test can
 * compare the statements two requests ran.
 */
internal fun Connection.listening(seam: (String) -> Unit): Connection {
    val raw = this
    return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement" || method.name == "createStatement") seam((args?.firstOrNull() as? String) ?: method.name)
        try {
            method.invoke(raw, *(args ?: emptyArray()))
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw failure.targetException
        }
    } as Connection
}
