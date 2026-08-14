package io.stride.spikes.appstore

/**
 * What Stride knows about a package that is already on the device. Kept deliberately free of
 * `PackageInfo` so the policy below is testable without Android.
 */
data class InstalledApp(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
)

/** The device facts an update decision depends on. */
data class DeviceProfile(
    val sdkInt: Int,
    /** Supported ABIs, most-preferred first, as reported by `Build.SUPPORTED_ABIS`. */
    val supportedAbis: List<String>,
    /** Stride's own package name, so the self-update can be recognised without a hardcoded string. */
    val selfPackage: String,
)

/** Why an available artifact is not offered. Shown to the user rather than silently dropped. */
enum class IneligibleReason {
    /** The artifact needs a newer Android than this console runs. */
    SDK_TOO_OLD,

    /** No ABI in common with this device. */
    ABI_MISMATCH,
}

/** One row of a computed plan. */
sealed interface PlanItem {
    val entry: CatalogEntry
    val packageName: String get() = entry.packageName
}

/** Installed, and the catalog has something newer. */
data class UpdateAvailable(
    override val entry: CatalogEntry,
    val installed: InstalledApp,
    /**
     * True when this is Stride upgrading itself. Load-bearing: a self-update kills the process, and
     * with it the overlay that supplies the only Back/Home this console has, so it is never
     * automatic and is offered as its own distinctly-worded action.
     */
    val isSelfUpdate: Boolean,
) : PlanItem

/** Offered by the catalog and not installed at all. */
data class NotInstalled(override val entry: CatalogEntry) : PlanItem

/** Installed and current. Kept in the plan so the UI can say so rather than show an empty list. */
data class UpToDate(
    override val entry: CatalogEntry,
    val installed: InstalledApp,
) : PlanItem

/** Present in the catalog but unusable on this device. */
data class Ineligible(
    override val entry: CatalogEntry,
    val reason: IneligibleReason,
) : PlanItem

/**
 * The decision layer between "here is a catalog" and "install this file".
 *
 * Pure by design: no `Context`, no I/O, no clock. Every rule that decides whether an APK gets
 * installed on a treadmill console is expressible as a function of the catalog, what is installed,
 * and the device — and is therefore covered by JVM unit tests instead of by running it on hardware.
 */
object UpdatePlan {

    /**
     * Classifies every catalog entry against the device.
     *
     * Entries are returned in a stable, deliberately opinionated order — actionable work first
     * (updates, then the self-update, then things not installed), inert rows last — so the launcher
     * can render the list without re-sorting and without inventing a priority of its own.
     */
    fun compute(
        catalog: CatalogManifest,
        installed: List<InstalledApp>,
        device: DeviceProfile,
    ): List<PlanItem> {
        val byPackage = installed.associateBy { it.packageName }

        val items = catalog.apps.map { entry ->
            val ineligible = ineligibility(entry, device)
            if (ineligible != null) return@map Ineligible(entry, ineligible)

            val current = byPackage[entry.packageName]
                ?: return@map NotInstalled(entry)

            // Strictly greater. Re-installing an equal versionCode is a no-op the platform would
            // reject anyway, and offering a *downgrade* would be actively wrong: on a non-rooted
            // device it fails, and where it succeeds it is how you brick a launcher.
            if (entry.versionCode > current.versionCode) {
                UpdateAvailable(
                    entry = entry,
                    installed = current,
                    isSelfUpdate = isSelf(entry, device),
                )
            } else {
                UpToDate(entry, current)
            }
        }

        return items.sortedWith(
            compareBy(
                { item ->
                    when (item) {
                        is UpdateAvailable -> if (item.isSelfUpdate) 1 else 0
                        is NotInstalled -> 2
                        is UpToDate -> 3
                        is Ineligible -> 4
                    }
                },
                { it.entry.name.lowercase() },
            )
        )
    }

    /**
     * The updates Stride may act on without being asked twice. Excludes the self-update by
     * construction — see [UpdateAvailable.isSelfUpdate].
     */
    fun backgroundInstallable(plan: List<PlanItem>): List<UpdateAvailable> =
        plan.filterIsInstance<UpdateAvailable>().filterNot { it.isSelfUpdate }

    /** Stride's own pending upgrade, if there is one. Always requires an explicit user action. */
    fun selfUpdate(plan: List<PlanItem>): UpdateAvailable? =
        plan.filterIsInstance<UpdateAvailable>().firstOrNull { it.isSelfUpdate }

    /**
     * The count the launcher badges. Third-party updates plus Stride's own; things merely *offered*
     * but never installed are not "updates" and must not nag as if they were.
     */
    fun pendingCount(plan: List<PlanItem>): Int = plan.count { it is UpdateAvailable }

    /**
     * SAFETY GATE (`PLAN.md` section 5, and `docs/APPSTORE.md`).
     *
     * A `PackageInstaller` confirmation is a full-screen system activity. Raising one while the belt
     * is moving covers the stop control, which is the one thing the overlay must never lose. So no
     * install is started unless the workout session is idle — downloads are free to continue, since
     * they draw nothing.
     *
     * Modelled as a plain boolean so the rule is unit-testable rather than buried in a service.
     */
    fun mayInstallNow(workoutIdle: Boolean, canRequestInstalls: Boolean): Boolean =
        workoutIdle && canRequestInstalls

    private fun isSelf(entry: CatalogEntry, device: DeviceProfile): Boolean =
        entry.role == CatalogRole.STRIDE || entry.packageName == device.selfPackage

    private fun ineligibility(entry: CatalogEntry, device: DeviceProfile): IneligibleReason? {
        if (entry.minSdk > device.sdkInt) return IneligibleReason.SDK_TOO_OLD
        // An empty abis list means "universal / no native code", which every device can run.
        if (entry.abis.isNotEmpty() && entry.abis.none { it in device.supportedAbis }) {
            return IneligibleReason.ABI_MISMATCH
        }
        return null
    }
}
