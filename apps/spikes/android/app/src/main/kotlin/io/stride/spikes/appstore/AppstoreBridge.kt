package io.stride.spikes.appstore

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import io.stride.spikes.StrideAccessibilityService
import io.stride.spikes.StrideNotificationListener
import io.stride.spikes.WorkoutSession

/**
 * The method-channel face of the app store.
 *
 * Kept out of [io.stride.spikes.SpikeBridge] itself, which is already long, and structured the same
 * way every other spike surface is: Flutter polls a snapshot map, because the state lives in a
 * service that outlives the Flutter engine.
 *
 * Nothing here decides anything. Policy is in [UpdatePlan] and [StrideAppstoreService]; this only
 * flattens it for the channel.
 */
class AppstoreBridge(private val context: Context) {

    /** Everything the launcher needs to draw the updates sheet, in one round trip. */
    fun status(): Map<String, Any?> {
        val plan = AppstoreState.plan
        val statuses = AppstoreState.allStatuses().associateBy { it.packageName }
        val catalog = AppstoreState.catalog
        val bundledPackages = catalog?.bundledPackages ?: emptySet()

        val items = plan.map { item ->
            val status = statuses[item.packageName]
            mapOf(
                "package" to item.packageName,
                "name" to item.entry.name,
                "kind" to item.kindName(),
                "isSelf" to (item is UpdateAvailable && item.isSelfUpdate),
                // Set when this package is only installable as part of a bundle. The launcher hides
                // these: "Google Services Framework Login" is not a thing anyone asked for, and
                // offering it on its own is offering a way to get half of Google Play.
                "bundleId" to catalog?.bundles?.firstOrNull {
                    item.packageName in it.packages
                }?.id,
                "availableVersionName" to item.entry.versionName,
                "availableVersionCode" to item.entry.versionCode,
                "installedVersionName" to item.installedVersionName(),
                "sizeBytes" to item.entry.sizeBytes,
                "releaseNotesUrl" to item.entry.releaseNotesUrl,
                // Only what this console has not read. A rider on versionCode 9 being offered 12
                // gets 10, 11 and 12; a rider already on 12 gets nothing, rather than being shown
                // the release they are running as if it were news.
                "releaseNotes" to item.entry.notesNewerThan(item.installedVersionCode()).map { note ->
                    mapOf(
                        "versionCode" to note.versionCode,
                        "versionName" to note.versionName,
                        "date" to note.date,
                        "notes" to note.notes,
                    )
                },
                "iconUrl" to item.entry.iconUrl,
                "ineligibleReason" to (item as? Ineligible)?.reason?.name?.lowercase(),
                "stage" to (status?.stage ?: AppstoreState.Stage.IDLE).name.lowercase(),
                "bytes" to (status?.bytes ?: 0L),
                "totalBytes" to (status?.totalBytes ?: item.entry.sizeBytes),
                "message" to status?.message,
            )
        }

        val installedPackages = plan.mapNotNull { item ->
            item.packageName.takeIf { item is UpToDate || item is UpdateAvailable }
        }.toSet()
        val run = AppstoreState.bundleRun
        val bundles = (catalog?.bundles ?: emptyList()).map { bundle ->
            val state = BundlePlan.state(bundle, installedPackages)
            mapOf(
                "id" to bundle.id,
                "name" to bundle.name,
                "detail" to bundle.detail,
                "iconUrl" to bundle.iconUrl,
                "state" to state.name.lowercase(),
                "installedCount" to BundlePlan.installedCount(bundle, installedPackages),
                "totalCount" to bundle.packages.size,
                "restartRequired" to bundle.restartRequired,
                "sizeBytes" to bundle.packages.sumOf {
                    catalog?.entryFor(it)?.totalBytes ?: 0L
                },
                "running" to (run?.bundleId == bundle.id && run.running),
                "failed" to (run?.bundleId == bundle.id && run.failed),
                "message" to run?.takeIf { it.bundleId == bundle.id }?.message,
                "restartPending" to (
                    run?.bundleId == bundle.id && run.restartRequired && !run.running
                    ),
            )
        }

        return mapOf(
            "checking" to AppstoreState.checking,
            "busy" to AppstoreState.busy(),
            "serviceRunning" to StrideAppstoreService.isRunning(),
            "lastCheckWallMs" to AppstoreState.lastCheckWallMs,
            "lastError" to AppstoreState.lastError,
            "catalogUrl" to StrideAppstoreService.catalogUrl(context),
            "pendingCount" to UpdatePlan.pendingCount(AppstoreState.plan, bundledPackages),
            "canRequestInstalls" to StrideAppstoreService.canRequestInstalls(context),
            "workoutIdle" to StrideAppstoreService.workoutIdle(),
            // The launcher disables install buttons on this rather than hiding them: an inert
            // control that explains itself beats a control that silently vanished mid-workout.
            "mayInstallNow" to UpdatePlan.mayInstallNow(
                StrideAppstoreService.workoutIdle(),
                StrideAppstoreService.canRequestInstalls(context),
            ),
            "holdReason" to StrideAppstoreService.holdReason(context),
            "items" to items,
            "bundles" to bundles,
        )
    }

    fun checkNow(): Boolean {
        StrideAppstoreService.check(context)
        AppstoreWorker.ensureScheduled(context)
        return true
    }

    /**
     * User-initiated install. This is the *only* path by which Stride upgrades itself, which is why
     * it exists separately from the automatic third-party path in the service.
     */
    fun install(packageName: String): Boolean {
        StrideAppstoreService.install(context, packageName)
        return true
    }

    /** Forget a failed or parked item so the row stops shouting. Does not abandon a live session. */
    fun cancel(packageName: String): Boolean {
        AppstoreState.clearStatus(packageName)
        return true
    }

    /**
     * Install every member of a bundle, in the catalog's order, from one tap.
     *
     * Each member still raises its own system confirmation — nothing here bypasses consent. What it
     * removes is the need for the rider to know that Google Play is four packages, which order they
     * go in, and that one of them is a split install.
     */
    fun installBundle(bundleId: String): Boolean {
        StrideAppstoreService.installBundle(context, bundleId)
        return true
    }

    /** Dismiss a finished or failed bundle run so its message stops occupying the sheet. */
    fun clearBundle(): Boolean {
        AppstoreState.clearBundleRun()
        return true
    }

    fun canRequestInstalls(): Boolean = StrideAppstoreService.canRequestInstalls(context)

    /**
     * Opens the per-app "install unknown apps" screen. API 26 replaced the global unknown-sources
     * toggle with this, and it is the one permission in the checklist a user can actually grant
     * from the console itself.
     */
    fun openInstallPermission(): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    fun catalogUrl(): String = StrideAppstoreService.catalogUrl(context)

    fun setCatalogUrl(url: String): Boolean = StrideAppstoreService.setCatalogUrl(context, url)

    /**
     * What still has to be true for Stride to work, computed on the device rather than written in a
     * README nobody has in front of them while standing on a treadmill.
     *
     * Ordered by consequence: the things whose absence breaks the console outright come first. Each
     * row carries the exact `adb` command where no in-app grant exists — on this hardware several of
     * these genuinely cannot be granted from the device, and pretending otherwise wastes the user's
     * time.
     */
    fun setupChecklist(): List<Map<String, Any?>> {
        val pkg = context.packageName
        return listOf(
            step(
                id = "install",
                title = "Allow Stride to install apps",
                detail = "Needed to update Stride and install pinned apps. Every install is still confirmed by you.",
                done = StrideAppstoreService.canRequestInstalls(context),
                adb = "adb shell appops set $pkg REQUEST_INSTALL_PACKAGES allow",
                inAppAction = "openInstallPermission",
            ),
            step(
                id = "overlay",
                title = "Draw over other apps",
                detail = "The workout HUD and the stop control are overlay windows. Without this there is no HUD.",
                done = Settings.canDrawOverlays(context),
                adb = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
            ),
            step(
                id = "accessibility",
                title = "Accessibility service (Back / Recents)",
                detail = "The console has no Back button. This service is the only way a non-system app can send one.",
                done = StrideAccessibilityService.isConnected(),
                adb = "adb shell settings put secure enabled_accessibility_services " +
                    "$pkg/$pkg.StrideAccessibilityService && " +
                    "adb shell settings put secure accessibility_enabled 1",
            ),
            step(
                id = "notifications",
                title = "Media session access",
                detail = "Lets Stride pause Spotify when the workout pauses, and show what is playing.",
                done = StrideNotificationListener.isConnected,
                adb = "adb shell cmd notification allow_listener $pkg/$pkg.StrideNotificationListener",
            ),
            step(
                id = "home",
                title = "Stride is the default launcher",
                detail = "Read docs/RUNBOOK.md first: a launcher that fails to start leaves a console you cannot operate.",
                done = isDefaultHome(),
            ),
            step(
                id = "catalog",
                title = "Update catalog reachable",
                detail = AppstoreState.lastError
                    ?: "Last checked: ${describeLastCheck()}",
                done = AppstoreState.lastError == null && AppstoreState.lastCheckWallMs > 0L,
                inAppAction = "checkNow",
            ),
        )
    }

    private fun describeLastCheck(): String =
        if (AppstoreState.lastCheckWallMs <= 0L) "never" else "recently"

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == context.packageName
    }

    private fun step(
        id: String,
        title: String,
        detail: String,
        done: Boolean,
        adb: String? = null,
        inAppAction: String? = null,
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "detail" to detail,
        "done" to done,
        "adb" to adb,
        "action" to inAppAction,
    )

    private fun PlanItem.kindName(): String = when (this) {
        is UpdateAvailable -> "update"
        is NotInstalled -> "notInstalled"
        is UpToDate -> "upToDate"
        is Ineligible -> "ineligible"
    }

    private fun PlanItem.installedVersionName(): String? = when (this) {
        is UpdateAvailable -> installed.versionName
        is UpToDate -> installed.versionName
        else -> null
    }

    /** Null when the app is not on the device, which is what tells the notes window to stand down. */
    private fun PlanItem.installedVersionCode(): Long? = when (this) {
        is UpdateAvailable -> installed.versionCode
        is UpToDate -> installed.versionCode
        else -> null
    }
}

/** Reads as documentation at the call site: the gate is about the belt, not about the UI. */
internal fun workoutIsIdle(): Boolean = WorkoutSession.state == WorkoutSession.State.IDLE
