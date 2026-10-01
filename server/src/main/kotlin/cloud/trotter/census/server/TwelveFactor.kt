package cloud.trotter.census.server

/**
 * Deploy contract marker (#1157 S1): see [Config] and TwelveFactorGuardTest.
 * No local-state probing is needed; the container root is read-only, /tmp is
 * ephemeral, and source guards keep filesystem writes and wall-clock calls explicit.
 */
object TwelveFactor
