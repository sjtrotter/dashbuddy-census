package cloud.trotter.census.server.ops

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KGateTest {
    @Test
    fun `notification channel follows every live gate transition`() {
        val sample = cloud.trotter.census.server.notificationFixture().toString()
        for ((installs, trusted, visible) in listOf(Triple(9, false, false), Triple(10, false, true),
            Triple(9, false, false), Triple(0, true, true), Triple(0, false, false))) {
            val rendered = SkeletonRender.renderNotification(sample, unblinded(installs, trusted, 10))
            org.junit.jupiter.api.Assertions.assertEquals(visible, rendered.channelId != null)
        }
    }

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
