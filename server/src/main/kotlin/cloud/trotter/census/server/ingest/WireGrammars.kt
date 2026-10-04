package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusFingerprint

/** Shared, pure grammars for retained wire tokens. */
object WireGrammars {
    fun interface TokenGrammar {
        fun matches(value: String): Boolean
    }

    val fingerprint = TokenGrammar(CensusFingerprint::isWellFormed)
    val platform = Regex("^[a-z_][a-z0-9_]{0,31}$")
    val platformAppVersion = Regex("""^[0-9]{1,5}(\.[0-9]{1,5}){0,3}$""")
    val appVersion = Regex("""^([0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}(\+([0-9a-f]{7,40}(\.dirty)?|nogit))?|test)$""")
    val rulesetReleaseTag = Regex("""^(corpus|dev|v?[0-9]{1,5}(\.[0-9]{1,5}){0,3}(-[a-z0-9]{1,12})?)$""")
    val ruleId = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$""")
    val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    val pipelineId = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    val stateMachineApiVersion = Regex("""^[0-9]{1,5}(\.[0-9]{1,5}){0,3}$""")
    val textKeyShape = Regex("^[a-z][A-Za-z0-9]{0,15}$")
}
