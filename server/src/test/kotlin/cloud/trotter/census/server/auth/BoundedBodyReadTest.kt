package cloud.trotter.census.server.auth

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Review (Astra, S3 round 3): a body that never finishes must time out, not hold an admission permit forever. */
class BoundedBodyReadTest {
    @Test
    fun `a complete body under the limit is returned`() = runTest {
        val read = readBounded(ByteReadChannel("hello".toByteArray()), limit = 16, timeoutMs = 1_000)
        assertTrue(read is BoundedRead.Ok)
        assertEquals("hello", String((read as BoundedRead.Ok).bytes))
    }

    @Test
    fun `a body over the limit is rejected`() = runTest {
        val read = readBounded(ByteReadChannel(ByteArray(17)), limit = 16, timeoutMs = 1_000)
        assertEquals(BoundedRead.TooLarge, read)
    }

    @Test
    fun `a dripping body hits the deadline`() = runTest {
        val channel = ByteChannel()
        val writer = launch { channel.writeFully(byteArrayOf(1)); channel.flush() } // one byte, then silence
        val read = readBounded(channel, limit = 16, timeoutMs = 200)
        writer.cancel()
        assertEquals(BoundedRead.Timeout, read)
    }
}
