package cloud.trotter.census.server

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing for "Ask the developer" items (#81, ADR-0011 §15): each artifact is encrypted on receipt to the operator's
 * X25519 public key, so the server stores, and can read back, only ciphertext. The private key never reaches the
 * server: the operator keeps it offline and opens items with `deploy/tools/QuarantineKey.java` (JDK only), which
 * implements the same format. JDK cryptography only (CONTRIBUTING).
 *
 * Format v1, all in one byte string:
 * `0x01 ‖ ephemeral public key (X.509, 44 bytes) ‖ nonce (12 bytes) ‖ AES-256-GCM ciphertext and 16-byte tag`.
 * The AES key is HKDF-SHA256 (RFC 5869) over the X25519 shared secret, salt = the ephemeral key (X.509), info =
 * [label] of the item id. The same label is the GCM associated data, so an item's ciphertext moved to
 * another item id does not open.
 */
object QuarantineCrypto {
    /** The environment variable holding the operator's public key: base64 of its X.509 encoding. */
    const val ENV: String = "CENSUS_OPERATOR_PUBLIC_KEY"
    private const val VERSION: Byte = 1
    private const val X25519_X509_BYTES = 44
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** The context bound into an item's key and associated data. */
    fun label(itemId: UUID): ByteArray = "census.quarantine.v1:$itemId".toByteArray(Charsets.UTF_8)

    /** Parses the configured key; the error names the variable, never the value. */
    fun publicKey(base64: String): PublicKey {
        val key = try {
            KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid variable: $ENV")
        }
        require(key is XECPublicKey && (key.params as? NamedParameterSpec)?.name == "X25519" &&
            key.encoded.size == X25519_X509_BYTES) { "Invalid variable: $ENV" }
        return key
    }

    /** Encrypts [plaintext] for the holder of [operator]'s private key, bound to [itemId]. */
    fun seal(operator: PublicKey, itemId: UUID, plaintext: ByteArray): ByteArray {
        val ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val shared = KeyAgreement.getInstance("X25519").run {
            init(ephemeral.private)
            doPhase(operator, true)
            generateSecret()
        }
        val ephemeralBytes = ephemeral.public.encoded
        check(ephemeralBytes.size == X25519_X509_BYTES)
        val key = hkdf(shared, ephemeralBytes, label(itemId))
        shared.fill(0)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(label(itemId))
                doFinal(plaintext)
            }
        } finally {
            key.fill(0)
        }
        return byteArrayOf(VERSION) + ephemeralBytes + nonce + sealed
    }

    /** HKDF-SHA256 extract-then-expand to 32 bytes (RFC 5869). */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        prk.fill(0)
        return expand.doFinal(info + byteArrayOf(1))
    }
}
