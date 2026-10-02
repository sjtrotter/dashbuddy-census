package cloud.trotter.census.server

/**
 * Environment-only deployment configuration (#1157 S1).
 *
 * Processes are disposable and keep no durable local state: PostgreSQL owns data,
 * stdout owns logs, and only java.io.tmpdir may hold temporary files. Deploy the
 * same immutable image with injected configuration; scale through backing services.
 * Secrets have no defaults and validation errors never include environment values.
 */
data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val publicHost: String,
    val operatorTokenSha256: String,
    val serverVersion: String = "dev",
    val imageDigest: String? = null,
    val operatorTotpSecret: String? = null,
    val alertsTopicArn: String? = null,
) {
    init {
        require(operatorTotpSecret == null || cloud.trotter.census.server.ops.Totp.validSecret(operatorTotpSecret)) {
            "Invalid variable: OPERATOR_TOTP_SECRET"
        }
        require(alertsTopicArn == null || alertsTopicArnPattern.matches(alertsTopicArn)) {
            "Invalid variable: ALERTS_TOPIC_ARN"
        }
    }

    // Fixed: the Compose healthcheck and Caddy upstream assume this port.
    val port: Int = 8080

    override fun toString(): String = "Config([redacted])"

    /** Validates configuration without echoing supplied values (#1157 S1). */
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing required variable: $name")

            val databaseUrl = required("DATABASE_URL")
            require(databaseUrl.startsWith("jdbc:postgresql:")) { "Invalid variable: DATABASE_URL" }
            val databaseUser = required("DATABASE_USER")
            val databasePassword = required("DATABASE_PASSWORD")
            val publicHost = required("PUBLIC_HOST")
            val operatorTokenSha256 = required("OPERATOR_TOKEN_SHA256")
            require(operatorTokenSha256.length == 64 && operatorTokenSha256.all { it.isHexDigit() }) {
                "Invalid variable: OPERATOR_TOKEN_SHA256"
            }

            return Config(
                databaseUrl = databaseUrl,
                databaseUser = databaseUser,
                databasePassword = databasePassword,
                publicHost = publicHost,
                operatorTokenSha256 = operatorTokenSha256.lowercase(),
                serverVersion = env["SERVER_VERSION"]?.takeIf { it.isNotBlank() } ?: "dev",
                operatorTotpSecret = env["OPERATOR_TOTP_SECRET"],
                alertsTopicArn = env["ALERTS_TOPIC_ARN"],
                imageDigest = env["IMAGE_DIGEST"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

internal fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal val alertsTopicArnPattern = Regex("^arn:aws:sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]{1,256}$")
