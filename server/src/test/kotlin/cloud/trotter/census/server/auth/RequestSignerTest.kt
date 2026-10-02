package cloud.trotter.census.server.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

class RequestSignerTest {
    @Test
    fun `canonical bytes and fixed HMAC match the client vector`() {
        val expected = "POST\n/v1/nonce\n1790899200\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actual = RequestSigner.canonical("POST", "/v1/nonce?ignored=yes", "1790899200")
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        assertEquals(SIGNATURE, RequestSigner.sign(SECRET, actual))
        assertTrue(RequestSigner.verify(SECRET, actual, SIGNATURE))
        assertTrue(RequestSigner.verify(SECRET, actual, SIGNATURE)) // signatures are not replay prevention
    }

    @Test
    fun `tampered body path method or signature fails`() {
        val canonical = RequestSigner.canonical("POST", "/v1/nonce", "1790899200")
        listOf(
            RequestSigner.canonical("POST", "/v1/nonce", "1790899200", "{}".toByteArray()),
            RequestSigner.canonical("GET", "/v1/nonce", "1790899200"),
            RequestSigner.canonical("POST", "/v1/rotate", "1790899200"),
        ).forEach { assertFalse(RequestSigner.verify(SECRET, it, SIGNATURE)) }
        listOf("", SIGNATURE.uppercase(), SIGNATURE + "0", "v2=" + SIGNATURE.drop(3))
            .forEach { assertFalse(RequestSigner.verify(SECRET, canonical, it)) }
        var called = false
        assertTrue(RequestSigner.verify(SECRET, canonical, SIGNATURE) { expected, actual ->
            called = true
            MessageDigest.isEqual(expected, actual)
        })
        assertTrue(called)
    }

    @Test
    fun `timestamp boundaries are inclusive with overflow safe parsing`() {
        val now = Instant.ofEpochSecond(1790899200)
        listOf(-300L, 0L, 300L).forEach { assertTrue(RequestSigner.timestampInWindow((now.epochSecond + it).toString(), now)) }
        listOf(-301L, 301L).forEach { assertFalse(RequestSigner.timestampInWindow((now.epochSecond + it).toString(), now)) }
        listOf("1.0", "+1790899200", "01790899200", "", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "999999999999999999999")
            .forEach { assertFalse(RequestSigner.timestampInWindow(it, now)) }
    }

    companion object {
        const val SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val SIGNATURE = "v1=bcf991bb04da013f724bd200abe7e8352c68dbd7155aacbcb6a1a1dff94cbefd"
    }
}
