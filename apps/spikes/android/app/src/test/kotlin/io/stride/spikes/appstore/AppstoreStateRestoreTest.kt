package io.stride.spikes.appstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Restoring the last known catalog after a process restart.
 *
 * The bug this pins: the last-check *timestamp* was persisted but the catalog was not, so a launcher
 * restart inside the 30 minute guard suppressed the check and left the store with nothing to show.
 * The rider saw "not checked yet" and no header badge while an update was genuinely pending, and it
 * stayed that way until they happened to tap Check now.
 *
 * These are the two invariants worth holding: a restore reports the *original* check time rather
 * than pretending one just happened, and it never overwrites a live result - a check racing a
 * restart must win, since its data is newer than anything on disk.
 */
class AppstoreStateRestoreTest {

    @After
    fun tearDown() = AppstoreState.reset()

    private fun entry(pkg: String, versionCode: Long) = CatalogEntry(
        packageName = pkg,
        role = CatalogRole.APP,
        name = pkg,
        versionCode = versionCode,
        versionName = "v$versionCode",
        minSdk = 26,
        abis = listOf("arm64-v8a"),
        url = "https://example.test/$pkg.apk",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
        signerSha256 = "b".repeat(64),
        releaseNotesUrl = null,
        requiresGms = false,
    )

    private fun catalog(vararg entries: CatalogEntry) =
        CatalogManifest(schema = 1, generated = null, apps = entries.toList())

    private fun planFor(catalog: CatalogManifest, installedVersion: Long) = UpdatePlan.compute(
        catalog,
        listOf(InstalledApp("com.example.app", installedVersion, "v$installedVersion")),
        DeviceProfile(
            sdkInt = 28,
            supportedAbis = listOf("arm64-v8a"),
            selfPackage = "io.stride.spikes",
        ),
    )

    @Test
    fun `restore surfaces the pending update and the time it was actually found`() {
        val cat = catalog(entry("com.example.app", versionCode = 9))
        val checkedAt = 1_700_000_000_000L

        AppstoreState.restore(cat, planFor(cat, installedVersion = 8), checkedAt)

        assertNotNull(AppstoreState.catalog)
        assertEquals(checkedAt, AppstoreState.lastCheckWallMs)
        assertEquals(1, AppstoreState.plan.filterIsInstance<UpdateAvailable>().size)
    }

    @Test
    fun `restore does not claim a check just happened`() {
        val cat = catalog(entry("com.example.app", versionCode = 9))
        AppstoreState.restore(cat, planFor(cat, installedVersion = 8), 1_700_000_000_000L)

        // elapsedRealtime is measured from boot, so a value carried across a restart is not
        // comparable. Freshness must fail towards checking again, never towards trusting disk.
        assertEquals(0L, AppstoreState.lastCheckElapsedMs)
    }

    @Test
    fun `restore never overwrites state that is already populated`() {
        // The real case is a check that races a restart and lands first: its data is newer than
        // anything on disk, so the cache must not clobber it. Exercised through a second restore
        // because completeCheck stamps SystemClock.elapsedRealtime, which is not available off
        // hardware - the guard being tested is the same one either way.
        val live = catalog(entry("com.example.app", versionCode = 12))
        AppstoreState.restore(live, planFor(live, installedVersion = 12), 500L)

        val stale = catalog(entry("com.example.app", versionCode = 9))
        AppstoreState.restore(stale, planFor(stale, installedVersion = 8), 1L)

        assertEquals(500L, AppstoreState.lastCheckWallMs)
        assertEquals(12L, AppstoreState.catalog!!.apps.single().versionCode)
        assertEquals(0, AppstoreState.plan.filterIsInstance<UpdateAvailable>().size)
    }
}
