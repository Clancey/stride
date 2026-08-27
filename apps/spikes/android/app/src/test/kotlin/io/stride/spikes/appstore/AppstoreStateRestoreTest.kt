package io.stride.spikes.appstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val generation = AppstoreState.beginInitialization()

        AppstoreState.restore(cat, planFor(cat, installedVersion = 8), checkedAt, generation)

        assertNotNull(AppstoreState.catalog)
        assertEquals(AppstoreState.Initialization.READY, AppstoreState.initialization)
        assertEquals(checkedAt, AppstoreState.lastCheckWallMs)
        assertEquals(1, AppstoreState.plan.filterIsInstance<UpdateAvailable>().size)
    }

    @Test
    fun `restore does not claim a check just happened`() {
        val cat = catalog(entry("com.example.app", versionCode = 9))
        val generation = AppstoreState.beginInitialization()
        AppstoreState.restore(
            cat,
            planFor(cat, installedVersion = 8),
            1_700_000_000_000L,
            generation,
        )

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
        val generation = AppstoreState.beginInitialization()
        AppstoreState.restore(live, planFor(live, installedVersion = 12), 500L, generation)

        val stale = catalog(entry("com.example.app", versionCode = 9))
        AppstoreState.restore(stale, planFor(stale, installedVersion = 8), 1L)

        assertEquals(500L, AppstoreState.lastCheckWallMs)
        assertEquals(12L, AppstoreState.catalog!!.apps.single().versionCode)
        assertEquals(0, AppstoreState.plan.filterIsInstance<UpdateAvailable>().size)
    }

    @Test
    fun `late cached restore cannot overwrite a check that started after it`() {
        val stale = catalog(entry("com.example.app", versionCode = 9))
        val generation = AppstoreState.beginInitialization()

        AppstoreState.beginCheck()
        val restored = AppstoreState.restore(
            stale,
            planFor(stale, installedVersion = 8),
            1L,
            generation,
        )

        assertEquals(false, restored)
        assertEquals(AppstoreState.Initialization.LOADING, AppstoreState.initialization)
        assertEquals(null, AppstoreState.catalog)
    }

    @Test
    fun `failed initialization is distinguishable from an empty catalog`() {
        val generation = AppstoreState.beginInitialization()

        AppstoreState.failInitialization(generation, "cache corrupt")
        val snapshot = AppstoreState.snapshot()

        assertEquals(AppstoreState.Initialization.FAILED, snapshot.initialization)
        assertEquals("cache corrupt", snapshot.lastError)
        assertEquals(null, snapshot.catalog)
    }

    @Test
    fun `a check that cannot start resolves loading as failed`() {
        AppstoreState.beginInitialization()
        val generation = AppstoreState.beginCheck()

        AppstoreState.failCheck(generation, "update service could not start")
        val snapshot = AppstoreState.snapshot()

        assertEquals(AppstoreState.Initialization.FAILED, snapshot.initialization)
        assertEquals(false, snapshot.checking)
        assertEquals("update service could not start", snapshot.lastError)
    }

    @Test
    fun `stale success cannot publish or finish a newer check`() {
        val staleCatalog = catalog(entry("com.example.app", versionCode = 9))
        val currentCatalog = catalog(entry("com.example.app", versionCode = 12))
        val stale = AppstoreState.beginCheck()
        val current = AppstoreState.beginCheck()

        assertFalse(
            AppstoreState.completeCheck(
                stale,
                staleCatalog,
                planFor(staleCatalog, installedVersion = 8),
            ),
        )
        assertTrue(AppstoreState.snapshot().checking)
        assertEquals(null, AppstoreState.snapshot().catalog)

        assertTrue(
            AppstoreState.completeCheck(
                current,
                currentCatalog,
                planFor(currentCatalog, installedVersion = 8),
            ),
        )
        assertFalse(AppstoreState.snapshot().checking)
        assertEquals(12L, AppstoreState.snapshot().catalog!!.apps.single().versionCode)
    }

    @Test
    fun `stale failure cannot fail or finish a newer check`() {
        val stale = AppstoreState.beginCheck()
        val current = AppstoreState.beginCheck()

        assertFalse(AppstoreState.failCheck(stale, "old request failed"))
        val stillChecking = AppstoreState.snapshot()
        assertTrue(stillChecking.checking)
        assertEquals(null, stillChecking.lastError)

        assertTrue(AppstoreState.failCheck(current, "current request failed"))
        val failed = AppstoreState.snapshot()
        assertFalse(failed.checking)
        assertEquals("current request failed", failed.lastError)
    }
}
