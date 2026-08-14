package io.stride.spikes.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Google Play restore is four installs in an order that matters, driven one at a time by a
 * runner that re-reads the device between steps. That makes the *ordering* and the *resume* the two
 * behaviours worth pinning: getting either wrong on hardware costs a reboot to find out.
 */
class BundlePlanTest {

    private val play = CatalogBundle(
        id = "google-play",
        name = "Google Play",
        detail = "Play Store and the services it needs",
        packages = listOf(
            "com.google.android.gsf.login",
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.android.vending",
        ),
        restartRequired = true,
    )

    @Test
    fun `a bare console is missing the whole bundle`() {
        assertEquals(BundleState.MISSING, BundlePlan.state(play, emptySet()))
        assertEquals(0, BundlePlan.installedCount(play, emptySet()))
    }

    @Test
    fun `next follows catalog order not alphabetical order`() {
        assertEquals("com.google.android.gsf.login", BundlePlan.next(play, emptySet()))
    }

    @Test
    fun `next skips what already landed and returns the first true gap`() {
        val installed = setOf("com.google.android.gsf.login", "com.google.android.gsf")
        assertEquals("com.google.android.gms", BundlePlan.next(play, installed))
        assertEquals(BundleState.PARTIAL, BundlePlan.state(play, installed))
        assertEquals(2, BundlePlan.installedCount(play, installed))
    }

    /**
     * A rider who cancels the Play Services dialog leaves a hole in the middle. The run must resume
     * at that hole rather than at the end, or Play never signs in.
     */
    @Test
    fun `an out of order install still resumes at the earliest missing member`() {
        val installed = setOf("com.google.android.gsf.login", "com.android.vending")
        assertEquals("com.google.android.gsf", BundlePlan.next(play, installed))
        assertEquals(
            listOf("com.google.android.gsf", "com.google.android.gms"),
            BundlePlan.remaining(play, installed),
        )
    }

    @Test
    fun `a complete bundle has nothing left to install`() {
        val installed = play.packages.toSet()
        assertEquals(BundleState.INSTALLED, BundlePlan.state(play, installed))
        assertNull(BundlePlan.next(play, installed))
        assertEquals(emptyList<String>(), BundlePlan.remaining(play, installed))
        assertEquals(4, BundlePlan.installedCount(play, installed))
    }

    /** Unrelated packages on the device must not be mistaken for progress. */
    @Test
    fun `other installed apps do not count towards the bundle`() {
        val installed = setOf("com.netflix.mediaclient", "com.spotify.music")
        assertEquals(BundleState.MISSING, BundlePlan.state(play, installed))
        assertEquals(0, BundlePlan.installedCount(play, installed))
    }
}
