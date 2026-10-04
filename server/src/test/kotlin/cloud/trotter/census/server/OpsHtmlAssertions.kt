package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertFalse

internal fun assertPrivate(page: String) {
    val withoutLinks = page.replace(Regex("href=\"/ops/clusters/[0-9a-f]{64}(?:/view|/draft|/draft\\.json5)?\""), "")
    assertFalse(Regex("(?i)[0-9a-f]{16}").containsMatchIn(withoutLinks))
    assertFalse(Regex("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").containsMatchIn(page))
    assertFalse(Regex("(?i)[0-9a-f]{4}(-[0-9a-f]{4}){3}").containsMatchIn(withoutLinks))
    for (attribute in Regex("""[\w:-]+\s*=\s*("[^"]*"|'[^']*')""").findAll(withoutLinks)) {
        val compact = attribute.groupValues[1].drop(1).dropLast(1).replace("-", "").replace(" ", "")
        assertFalse(Regex("(?i)[0-9a-f]{12,}").containsMatchIn(compact))
    }
    assertFalse(page.contains("~"))
}
