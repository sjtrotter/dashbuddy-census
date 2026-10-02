package cloud.trotter.census.server.ops

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KGateTest {
    @Test
    fun `only k nontrusted installs or a trusted sighting unblind`() {
        assertFalse(unblinded(9, false, 10))
        assertTrue(unblinded(10, false, 10))
        assertTrue(unblinded(11, false, 10))
        assertTrue(unblinded(0, true, 10))
        assertTrue(unblinded(1, false, 1))
        assertFalse(unblinded(0, false, 1))
    }
}
