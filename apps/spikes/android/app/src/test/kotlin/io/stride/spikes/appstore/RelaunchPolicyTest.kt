package io.stride.spikes.appstore

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaunchPolicyTest {

    @Test
    fun `relaunches when Stride itself was replaced`() {
        assertTrue(RelaunchPolicy.shouldRelaunch(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    /**
     * The distinction that matters. PACKAGE_REPLACED fires for every app on the device, so acting
     * on it would drag a rider out of Spotify the moment Spotify updated itself.
     */
    @Test
    fun `ignores another app being replaced`() {
        assertFalse(RelaunchPolicy.shouldRelaunch(Intent.ACTION_PACKAGE_REPLACED))
    }

    @Test
    fun `ignores install and boot broadcasts`() {
        assertFalse(RelaunchPolicy.shouldRelaunch(Intent.ACTION_PACKAGE_ADDED))
        assertFalse(RelaunchPolicy.shouldRelaunch(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `ignores a null action`() {
        assertFalse(RelaunchPolicy.shouldRelaunch(null))
    }
}
