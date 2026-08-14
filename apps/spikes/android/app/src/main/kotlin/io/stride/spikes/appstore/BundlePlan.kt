package io.stride.spikes.appstore

/**
 * An ordered set of packages that are only useful together.
 *
 * The motivating case is Google Play. This console ships as AOSP with no Google apps, and restoring
 * Play is not one install but four, in an order that matters: Google Services Framework Login, then
 * GSF, then Play Services (a split install - base plus a density config), then the Play Store. Get
 * the order wrong and the later ones fail; stop halfway and you have a Play Store icon that opens
 * onto a sign-in loop, which is worse than not having it at all.
 *
 * That sequence is knowledge about *the artifacts*, so it lives in the catalog next to them rather
 * than being hardcoded in the launcher. A console can then learn a corrected order from a catalog
 * update instead of an app update.
 *
 * @param packages install order. Every name is validated against the catalog's own apps at parse
 *   time, so the runner can assume each one resolves.
 * @param restartRequired whether the rider has to reboot for this to finish working. Stride cannot
 *   reboot an unrooted console itself, so all it can do is say so plainly at the end.
 */
data class CatalogBundle(
    val id: String,
    val name: String,
    val detail: String?,
    val packages: List<String>,
    val restartRequired: Boolean = false,
    val iconUrl: String? = null,
)

/** How much of a bundle is on the device. */
enum class BundleState {
    /** None of it. The one-tap install is offered. */
    MISSING,

    /**
     * Some of it. Offered as "finish installing" rather than hidden: a half-installed bundle is the
     * state most in need of the button, and the state a rider is most likely to reach by cancelling
     * one of the confirmations.
     */
    PARTIAL,

    /** All of it. The row disappears - there is nothing left to ask for. */
    INSTALLED,
}

/**
 * Decides what a bundle install should do next.
 *
 * Pure, like [UpdatePlan] and for the same reason: this is the logic that decides which APKs get
 * installed in which order on a machine with a motor, and it should be specified by unit tests
 * rather than discovered on a treadmill.
 */
object BundlePlan {

    /** Ordered members not yet on the device. Empty means there is nothing left to do. */
    fun remaining(bundle: CatalogBundle, installed: Set<String>): List<String> =
        bundle.packages.filterNot { it in installed }

    fun state(bundle: CatalogBundle, installed: Set<String>): BundleState {
        val missing = remaining(bundle, installed)
        return when {
            missing.isEmpty() -> BundleState.INSTALLED
            missing.size == bundle.packages.size -> BundleState.MISSING
            else -> BundleState.PARTIAL
        }
    }

    /**
     * The next package to install, or null when the bundle is complete.
     *
     * Deliberately recomputed from what is installed *now* rather than walking a stored cursor: the
     * install of one member can take minutes and end in a dialog the rider dismisses, and coming
     * back to a fresh reading of the device is how a resumed run avoids reinstalling something that
     * already landed.
     */
    fun next(bundle: CatalogBundle, installed: Set<String>): String? =
        remaining(bundle, installed).firstOrNull()

    /** Progress for the UI: how many members are done, out of how many. */
    fun installedCount(bundle: CatalogBundle, installed: Set<String>): Int =
        bundle.packages.count { it in installed }
}
