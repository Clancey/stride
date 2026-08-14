package io.stride.spikes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console ships launcher entries for factory test tools and background services, and Stride
 * itself appears in its own app list. None of those belong in the grid a rider picks from.
 *
 * The risk in this filter is over-reach, not under-reach: hiding something a rider needs on a
 * console with no physical buttons and no Play Store leaves them with no way to reach it. So the
 * tests that matter most here are the ones asserting what stays *visible*.
 */
class HiddenAppsTest {
    private val self = "io.stride.spikes"

    @Test
    fun `hides itself because the launcher cannot usefully launch the launcher`() {
        assertTrue(SpikeBridge.isHiddenFromLauncher(self, self))
    }

    @Test
    fun `hides iFit, whose only launcher entry is a factory test harness`() {
        // Not an uninstall and not a disable: this filter only decides what the grid and the pin
        // picker show. iFit stays installed, and cert extraction still reads its APK through
        // getApplicationInfo, which does not go through this list.
        assertTrue(SpikeBridge.isHiddenFromLauncher(SpikeBridge.IFIT_CONSOLE_PACKAGE, self))
    }

    @Test
    fun `keeps the apps riders actually use`() {
        val keep = listOf(
            "com.spotify.music",
            "org.jellyfin.mobile",
            "com.netflix.mediaclient",
            "org.chromium.chrome.stable",
            "org.fdroid.fdroid",
            "org.cagnulen.qdomyoszwift",
        )
        for (pkg in keep) {
            assertFalse("$pkg should stay visible", SpikeBridge.isHiddenFromLauncher(pkg, self))
        }
    }

    @Test
    fun `hides vendor and factory test entries`() {
        val hide = listOf(
            "com.cvte.mt8390.simpletest",
            "com.mediatek.bluetooth",
            "com.mediatek.gnss.nonframeworklbs",
            "com.mesh.test.provisioner",
            "com.ifit.glassos_service",
            "org.chromium.webview_shell",
            "com.android.traceur",
        )
        for (pkg in hide) {
            assertTrue("$pkg should be hidden", SpikeBridge.isHiddenFromLauncher(pkg, self))
        }
    }

    @Test
    fun `hides Android settings because Stride has a warned route to it`() {
        // Settings blanks our overlay, so a pinned tile is a one-way trip. The settings screen's
        // own button says so before the rider taps it; a grid tile would not.
        assertTrue(SpikeBridge.isHiddenFromLauncher("com.android.settings", self))
    }

    @Test
    fun `does not hide by prefix, so a real app with a vendor-ish name survives`() {
        assertFalse(SpikeBridge.isHiddenFromLauncher("com.mediatek.player", self))
        assertFalse(SpikeBridge.isHiddenFromLauncher("com.ifit.rivendell.beta", self))
    }
}
