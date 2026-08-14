package io.stride.spikes.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule that decides whether an APK gets installed on a console with a motor, pinned as pure
 * data. If a rule here is wrong, the failure mode is a treadmill launcher that restarts itself
 * mid-run — which is exactly why none of this lives inside a Service.
 */
class UpdatePlanTest {

    private val device = DeviceProfile(
        sdkInt = 28,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        selfPackage = "io.stride.spikes",
    )

    private fun entry(
        pkg: String = "com.example.app",
        role: CatalogRole = CatalogRole.APP,
        versionCode: Long = 5,
        minSdk: Int = 26,
        abis: List<String> = listOf("arm64-v8a"),
        name: String = pkg,
        requiresGms: Boolean = false,
    ) = CatalogEntry(
        packageName = pkg,
        role = role,
        name = name,
        versionCode = versionCode,
        versionName = "v$versionCode",
        minSdk = minSdk,
        abis = abis,
        url = "https://example.test/$pkg.apk",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
        signerSha256 = "b".repeat(64),
        releaseNotesUrl = null,
        requiresGms = requiresGms,
    )

    private fun catalog(vararg entries: CatalogEntry) =
        CatalogManifest(schema = 1, generated = null, apps = entries.toList())

    @Test
    fun `newer catalog version is an update`() {
        val plan = UpdatePlan.compute(
            catalog(entry(versionCode = 6)),
            listOf(InstalledApp("com.example.app", 5, "v5")),
            device,
        )
        val item = plan.single()
        assertTrue(item is UpdateAvailable)
        assertFalse((item as UpdateAvailable).isSelfUpdate)
    }

    @Test
    fun `equal version is up to date`() {
        val plan = UpdatePlan.compute(
            catalog(entry(versionCode = 5)),
            listOf(InstalledApp("com.example.app", 5, "v5")),
            device,
        )
        assertTrue(plan.single() is UpToDate)
    }

    @Test
    fun `older catalog version is never offered as a downgrade`() {
        val plan = UpdatePlan.compute(
            catalog(entry(versionCode = 4)),
            listOf(InstalledApp("com.example.app", 9, "v9")),
            device,
        )
        assertTrue(plan.single() is UpToDate)
        assertEquals(0, UpdatePlan.pendingCount(plan))
    }

    @Test
    fun `absent package is offered as not installed, not as an update`() {
        val plan = UpdatePlan.compute(catalog(entry()), emptyList(), device)
        assertTrue(plan.single() is NotInstalled)
        // Something merely available must not badge the launcher as if it were pending work.
        assertEquals(0, UpdatePlan.pendingCount(plan))
    }

    @Test
    fun `entry needing a newer android is ineligible`() {
        val plan = UpdatePlan.compute(catalog(entry(minSdk = 33)), emptyList(), device)
        assertEquals(IneligibleReason.SDK_TOO_OLD, (plan.single() as Ineligible).reason)
    }

    @Test
    fun `entry with no common abi is ineligible`() {
        val plan = UpdatePlan.compute(
            catalog(entry(abis = listOf("x86_64"))),
            emptyList(),
            device,
        )
        assertEquals(IneligibleReason.ABI_MISMATCH, (plan.single() as Ineligible).reason)
    }

    @Test
    fun `entry with no declared abis runs anywhere`() {
        val plan = UpdatePlan.compute(catalog(entry(abis = emptyList())), emptyList(), device)
        assertTrue(plan.single() is NotInstalled)
    }

    // ------------------------------------------------------------------ self update

    @Test
    fun `stride role is classified as a self update`() {
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "io.stride.spikes", role = CatalogRole.STRIDE, versionCode = 9)),
            listOf(InstalledApp("io.stride.spikes", 8, "v8")),
            device,
        )
        assertTrue(UpdatePlan.selfUpdate(plan) != null)
    }

    @Test
    fun `our own package is a self update even without the role`() {
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "io.stride.spikes", role = CatalogRole.APP, versionCode = 9)),
            listOf(InstalledApp("io.stride.spikes", 8, "v8")),
            device,
        )
        assertTrue((plan.single() as UpdateAvailable).isSelfUpdate)
    }

    @Test
    fun `self update is excluded from background installs`() {
        val plan = UpdatePlan.compute(
            catalog(
                entry(pkg = "io.stride.spikes", role = CatalogRole.STRIDE, versionCode = 9),
                entry(pkg = "com.example.app", versionCode = 6),
            ),
            listOf(
                InstalledApp("io.stride.spikes", 8, "v8"),
                InstalledApp("com.example.app", 5, "v5"),
            ),
            device,
        )
        val background = UpdatePlan.backgroundInstallable(plan)
        assertEquals(listOf("com.example.app"), background.map { it.packageName })
        // ...but it is still counted, so the rider is told about it.
        assertEquals(2, UpdatePlan.pendingCount(plan))
    }

    @Test
    fun `updates sort ahead of the self update, which sorts ahead of everything inert`() {
        val plan = UpdatePlan.compute(
            catalog(
                entry(pkg = "com.example.old", versionCode = 1, name = "Current"),
                entry(pkg = "io.stride.spikes", role = CatalogRole.STRIDE, versionCode = 9, name = "Stride"),
                entry(pkg = "com.example.app", versionCode = 6, name = "App"),
                entry(pkg = "com.example.new", versionCode = 2, name = "New"),
            ),
            listOf(
                InstalledApp("com.example.old", 1, "v1"),
                InstalledApp("io.stride.spikes", 8, "v8"),
                InstalledApp("com.example.app", 5, "v5"),
            ),
            device,
        )
        assertEquals(
            listOf("com.example.app", "io.stride.spikes", "com.example.new", "com.example.old"),
            plan.map { it.packageName },
        )
    }

    @Test
    fun `an empty catalog produces no plan and no self update`() {
        val plan = UpdatePlan.compute(catalog(), emptyList(), device)
        assertTrue(plan.isEmpty())
        assertNull(UpdatePlan.selfUpdate(plan))
    }

    // ------------------------------------------------------------------ the safety gate

    @Test
    fun `installs are refused while the workout is not idle`() {
        // The install confirmation is a full-screen system activity. Raising it mid-run covers the
        // stop control, which is the one thing the overlay must never lose.
        assertFalse(UpdatePlan.mayInstallNow(workoutIdle = false, canRequestInstalls = true))
    }

    @Test
    fun `installs are refused without the install permission`() {
        assertFalse(UpdatePlan.mayInstallNow(workoutIdle = true, canRequestInstalls = false))
    }

    @Test
    fun `installs are allowed when idle and permitted`() {
        assertTrue(UpdatePlan.mayInstallNow(workoutIdle = true, canRequestInstalls = true))
    }

    // ------------------------------------------------------------------ Google Play Services

    @Test
    fun `an app needing Play Services is ineligible on a console without it`() {
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "com.needs.gms", requiresGms = true)),
            installed = emptyList(),
            device = device.copy(hasGms = false),
        )

        val item = plan.single()
        assertTrue(item is Ineligible)
        assertEquals(IneligibleReason.NEEDS_GMS, (item as Ineligible).reason)
    }

    @Test
    fun `the same app is offered on a device that does have Play Services`() {
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "com.needs.gms", requiresGms = true)),
            installed = emptyList(),
            device = device.copy(hasGms = true),
        )

        assertTrue(plan.single() is NotInstalled)
    }

    @Test
    fun `apps not marked as needing Play Services are unaffected`() {
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "com.plain.app")),
            installed = emptyList(),
            device = device.copy(hasGms = false),
        )

        assertTrue(plan.single() is NotInstalled)
    }

    @Test
    fun `a GMS app that also targets the wrong abi reports the abi problem`() {
        // Ordering matters for the message the rider reads. "Not built for this console" is the
        // truer answer for something that would not have run here even with Play Services.
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "com.both", requiresGms = true, abis = listOf("x86_64"))),
            installed = emptyList(),
            device = device.copy(hasGms = false),
        )

        assertEquals(IneligibleReason.ABI_MISMATCH, (plan.single() as Ineligible).reason)
    }

    @Test
    fun `an ineligible GMS app is never background installable`() {
        // The load-bearing consequence: marking it ineligible must also keep it out of the set the
        // service installs unattended, not merely change what the row says.
        val plan = UpdatePlan.compute(
            catalog(entry(pkg = "com.needs.gms", requiresGms = true)),
            installed = listOf(InstalledApp("com.needs.gms", 1, "1")),
            device = device.copy(hasGms = false),
        )

        assertTrue(UpdatePlan.backgroundInstallable(plan).isEmpty())
        assertEquals(0, UpdatePlan.pendingCount(plan))
    }
}
