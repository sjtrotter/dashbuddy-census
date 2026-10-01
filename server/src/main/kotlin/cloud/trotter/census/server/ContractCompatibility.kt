package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusHash

/** Compile-time proof that the Apache-2.0 included build supplies the contract (#1157 S1). */
object ContractCompatibility {
    val hashType: Class<CensusHash> = CensusHash::class.java

    // TODO(#1157): Read the domain constant from the sibling contract when available.
    // The contract checkout was unavailable during S1 bootstrapping.
    const val HASH_DOMAIN: String = "census.v1"
}
