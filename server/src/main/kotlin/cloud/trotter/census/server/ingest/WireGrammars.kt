package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.WireTokenGrammars

/** Shared, pure grammars for retained wire tokens; the version and rule-id grammars are the contract's (#64). */
object WireGrammars {
    fun interface TokenGrammar {
        fun matches(value: String): Boolean
    }

    val fingerprint = TokenGrammar(CensusFingerprint::isWellFormed)
    val platform = Regex("^[a-z_][a-z0-9_]{0,31}$")
    val platformAppVersion = WireTokenGrammars.platformAppVersion
    val appVersion = WireTokenGrammars.appVersion
    val rulesetReleaseTag = WireTokenGrammars.rulesetReleaseTag
    val ruleId = WireTokenGrammars.ruleId
    val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    val pipelineId = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    val stateMachineApiVersion = Regex("""^[0-9]{1,5}(\.[0-9]{1,5}){0,3}$""")
    val textKeyShape = Regex("^[a-z][A-Za-z0-9]{0,15}$")
}
