package cloud.trotter.census.server.ops

/** #1175: trusted sightings unblind independently; trusted installs are excluded from the k count. */
fun unblinded(distinctNonTrustedInstalls: Int, seenByTrusted: Boolean, k: Int): Boolean =
    seenByTrusted || distinctNonTrustedInstalls >= k
