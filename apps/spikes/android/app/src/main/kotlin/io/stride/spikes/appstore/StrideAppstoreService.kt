package io.stride.spikes.appstore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import io.stride.spikes.WorkoutSession
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Stride app store: a background service that keeps the console's software current without a
 * laptop.
 *
 * It does four things, in this order, and nothing else:
 *
 * 1. Fetches the catalog ([CatalogManifest]) over HTTPS.
 * 2. Classifies it against what is installed ([UpdatePlan]).
 * 3. Downloads and verifies stale third-party artifacts ([ApkDownloader], [ApkVerifier]).
 * 4. Asks [ApkInstaller] to install them, user-confirmed.
 *
 * ### Why it is a service and not a Dart timer
 *
 * The same reason the overlay is (`PLAN.md` section 3.2): the Flutter engine dies whenever the rider
 * is inside Spotify, which is most of the time. Update checking that only runs while Stride is
 * foregrounded would never run on the machine it exists to maintain.
 *
 * ### Safety
 *
 * - **No install prompt while the belt is not idle.** A `PackageInstaller` confirmation is a
 *   full-screen activity; it would cover the stop control (`PLAN.md` section 3.9). Downloads are
 *   unrestricted — they draw nothing — but the install step waits for [WorkoutSession.State.IDLE].
 *   Work held this way resumes automatically when the session ends.
 * - **Stride never updates itself unprompted.** A self-install kills this process, and with it the
 *   overlay that supplies the only Back/Home the console has. [ACTION_INSTALL] on Stride's own
 *   package is only ever reached from an explicit tap in the launcher.
 * - **No motor path.** This class has no reference to the machine link and never will. It reads
 *   workout *state* to decide when to stay quiet; it cannot start, stop, or change anything
 *   physical.
 */
class StrideAppstoreService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "StrideAppstore") }
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val downloader: ApkDownloader by lazy {
        ApkDownloader(stagingDir(this), ApkDownloader.httpFetcher(http))
    }
    private val installer: ApkInstaller by lazy { ApkInstaller(this) }

    /**
     * When a workout ends, anything parked by the safety gate becomes installable. Without this the
     * rider would have to notice and retry by hand, which in practice means the update never lands.
     */
    private val workoutListener: (WorkoutSession.State) -> Unit = { state ->
        if (state == WorkoutSession.State.IDLE) {
            worker.execute { runCatching { installHeldItems() } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        startForegroundWithNotification()
        WorkoutSession.addListener(workoutListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_INSTALL -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                if (packageName != null) worker.execute { installNow(packageName) }
            }

            else -> worker.execute { check() }
        }
        // START_STICKY would have the platform restart us with a null intent after a kill, which
        // would silently turn a one-shot install request into a catalog check. That is harmless but
        // confusing; the periodic worker is what guarantees liveness, not stickiness.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WorkoutSession.removeListener(workoutListener)
        active = null
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ check

    /** Fetch, parse, classify, then act on whatever the plan says is safe to act on. */
    private fun check() {
        AppstoreState.beginCheck()
        val url = catalogUrl(this)
        val body = try {
            fetchCatalog(url)
        } catch (e: Exception) {
            AppstoreState.failCheck("could not reach the catalog: ${e.message}")
            Log.w(TAG, "catalog fetch failed from $url", e)
            return
        }

        val catalog = try {
            CatalogManifest.parse(body)
        } catch (e: CatalogFormatException) {
            AppstoreState.failCheck("catalog is not usable: ${e.message}")
            Log.w(TAG, "catalog rejected", e)
            return
        }

        val plan = UpdatePlan.compute(catalog, installedApps(), deviceProfile())
        AppstoreState.completeCheck(catalog, plan)

        // Third-party updates proceed on their own; Stride's own upgrade waits for a tap.
        UpdatePlan.backgroundInstallable(plan).forEach { item ->
            runCatching { stageAndInstall(item.entry) }
                .onFailure { Log.w(TAG, "update failed for ${item.packageName}", it) }
        }
    }

    private fun fetchCatalog(url: String): String {
        require(url.startsWith("https://")) { "catalog url must be https" }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw java.io.IOException("empty catalog body")
        }
    }

    // ---------------------------------------------------------------- install

    /**
     * Explicit, user-initiated install of one package — including Stride itself, which is the only
     * way Stride ever upgrades.
     */
    private fun installNow(packageName: String) {
        val entry = AppstoreState.catalog?.entryFor(packageName)
        if (entry == null) {
            AppstoreState.update(
                packageName,
                AppstoreState.Stage.FAILED,
                message = "no catalog entry; check for updates first",
            )
            return
        }
        runCatching { stageAndInstall(entry) }
            .onFailure { Log.w(TAG, "install failed for $packageName", it) }
    }

    /** Download (if needed), verify, then install or park. */
    private fun stageAndInstall(entry: CatalogEntry) {
        // A bundle is only staged if *every* part is present. A surviving subset from an
        // interrupted run would otherwise be treated as a complete download and installed without
        // its native-code split.
        val expected = entry.allArtifacts.associate { it.name to downloader.stagingFile(entry, it) }
        var staged: Map<String, File> = expected
        if (expected.values.any { !it.exists() }) {
            AppstoreState.update(
                entry.packageName,
                AppstoreState.Stage.DOWNLOADING,
                totalBytes = entry.totalBytes,
            )
            try {
                staged = downloader.downloadAll(entry) { progress ->
                    AppstoreState.progress(entry.packageName, progress.bytes, progress.totalBytes)
                }
            } catch (e: DownloadException) {
                AppstoreState.update(
                    entry.packageName,
                    AppstoreState.Stage.FAILED,
                    message = e.message,
                )
                return
            }
        }

        val base = staged.getValue("base")
        // Verification inspects the base APK: package, versionCode and signer all live there, and
        // Android will reject splits signed by anyone else at commit time.
        when (val verdict = ApkVerifier.verify(entry, ApkVerifier.inspect(this, base))) {
            is VerificationResult.Rejected -> {
                // Fail closed and destroy the evidence: a file that failed verification must never
                // be reachable by a later retry that happens to skip the check.
                staged.values.forEach { it.delete() }
                AppstoreState.update(
                    entry.packageName,
                    AppstoreState.Stage.FAILED,
                    message = verdict.detail,
                )
                return
            }

            VerificationResult.Ok -> Unit
        }

        if (!UpdatePlan.mayInstallNow(workoutIdle(), canRequestInstalls(this))) {
            AppstoreState.update(
                entry.packageName,
                AppstoreState.Stage.READY,
                message = holdReason(this),
            )
            return
        }

        runCatching { installer.install(entry, staged) }
            .onFailure {
                AppstoreState.update(
                    entry.packageName,
                    AppstoreState.Stage.FAILED,
                    message = "could not start the install: ${it.message}",
                )
            }
    }

    /** Everything downloaded and verified but parked by the safety gate. */
    private fun installHeldItems() {
        if (!UpdatePlan.mayInstallNow(workoutIdle(), canRequestInstalls(this))) return
        val catalog = AppstoreState.catalog ?: return
        AppstoreState.allStatuses()
            .filter { it.stage == AppstoreState.Stage.READY }
            .mapNotNull { catalog.entryFor(it.packageName) }
            // Stride's own upgrade stays parked: "the workout ended" is not consent to restart the
            // launcher.
            .filter { it.role != CatalogRole.STRIDE }
            .forEach { entry -> runCatching { stageAndInstall(entry) } }
    }

    // ----------------------------------------------------------------- device

    private fun installedApps(): List<InstalledApp> {
        val pm = packageManager
        // The <queries> block in the manifest is what makes this honest under package-visibility
        // filtering; we deliberately do not hold QUERY_ALL_PACKAGES (see AndroidManifest.xml).
        return pm.getInstalledPackages(0).mapNotNull { info ->
            val name = info.packageName.orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            InstalledApp(name, code, info.versionName ?: "")
        }
    }

    private fun deviceProfile(): DeviceProfile = DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        selfPackage = packageName,
        hasGms = hasUsableGms(),
    )

    /**
     * Whether Google Play Services is present *and privileged*, which is the only form of it worth
     * reporting true for.
     *
     * The flag check is the point. A user-space sideload of `com.google.android.gms` installs
     * fine and is then inert — it cannot hold the signature-level permissions it needs — so
     * presence alone would be a lie to every caller. `FLAG_SYSTEM` is what distinguishes a GMS that
     * came with the ROM from one somebody pushed with adb after reading a forum post.
     */
    private fun hasUsableGms(): Boolean = try {
        val info = packageManager.getApplicationInfo(GMS_PACKAGE, 0)
        info.enabled && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ----------------------------------------------------------- foreground

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stride updates",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Stride updates")
            .setContentText("Checking for app updates - no motor control")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_CHECK = "io.stride.spikes.APPSTORE_CHECK"
        const val ACTION_INSTALL = "io.stride.spikes.APPSTORE_INSTALL"
        const val ACTION_STOP = "io.stride.spikes.APPSTORE_STOP"
        const val EXTRA_PACKAGE = "io.stride.spikes.APPSTORE_PACKAGE"

        /**
         * Where the catalog lives: the public
         * [stride-catalog](https://github.com/Clancey/stride-catalog) repository, read directly as
         * a raw file. A static file in a public repo is the whole backend — no server to run, no
         * server to break, and every change to what a console will install is a reviewable commit.
         *
         * Overridable per device from the Updates sheet (stored in app-private prefs), so a machine
         * can be pointed at a staging catalog without shipping a second build. The override must be
         * https; see [setCatalogUrl].
         */
        const val DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/Clancey/stride-catalog/main/catalog.json"

        private const val PREFS = "stride_appstore"
        private const val KEY_CATALOG_URL = "catalog_url"
        private const val CHANNEL_ID = "stride_spikes_appstore"
        private const val NOTIFICATION_ID = 4322
        private const val TAG = "StrideAppstore"

        /**
         * Google Play Services. Referenced only to detect its absence — nothing here installs it,
         * because nothing unprivileged can. See [DeviceProfile.hasGms].
         */
        private const val GMS_PACKAGE = "com.google.android.gms"

        @Volatile
        private var active: StrideAppstoreService? = null

        fun isRunning(): Boolean = active != null

        fun catalogUrl(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CATALOG_URL, DEFAULT_CATALOG_URL) ?: DEFAULT_CATALOG_URL

        /** Rejects a non-https override rather than storing one we would refuse to use later. */
        fun setCatalogUrl(context: Context, url: String): Boolean {
            if (!url.startsWith("https://")) return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_CATALOG_URL, url) }
            return true
        }

        fun stagingDir(context: Context): File = File(context.cacheDir, "appstore").apply { mkdirs() }

        /** API 26+ gates sideloading per-app rather than with the old global "unknown sources". */
        fun canRequestInstalls(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()

        fun workoutIdle(): Boolean = WorkoutSession.state == WorkoutSession.State.IDLE

        /**
         * The backstop consulted by [InstallResultReceiver] before it raises the system's
         * confirmation dialog over whatever is on screen.
         */
        fun installUiAllowed(): Boolean = workoutIdle()

        /** Human-readable reason an install is parked, for the launcher sheet. */
        fun holdReason(context: Context): String = when {
            !workoutIdle() -> "waiting until the workout ends - an install dialog would cover the stop control"
            !canRequestInstalls(context) -> "Stride is not allowed to install apps yet"
            else -> "waiting"
        }

        // -------------------------------------------------------------- entry points

        fun check(context: Context) = start(context, ACTION_CHECK)

        fun install(context: Context, packageName: String) =
            start(context, ACTION_INSTALL) { it.putExtra(EXTRA_PACKAGE, packageName) }

        fun stop(context: Context) = start(context, ACTION_STOP)

        private fun start(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, StrideAppstoreService::class.java).setAction(action)
            configure(intent)
            context.startForegroundService(intent)
        }

        /**
         * Called from [InstallResultReceiver] once an install has succeeded or failed for good.
         * Drops the staged artifact — tens of megabytes we have no further use for — and refreshes
         * the plan so the launcher stops offering something already installed.
         */
        fun onInstallSettled(context: Context, packageName: String) {
            val entry = AppstoreState.catalog?.entryFor(packageName) ?: return
            runCatching { File(stagingDir(context), "$packageName-${entry.versionCode}.apk").delete() }
            check(context)
        }
    }
}

/** Convenience for the bridge: has the user granted the sideloading permission? */
internal fun Context.canRequestPackageInstallsCompat(): Boolean =
    StrideAppstoreService.canRequestInstalls(this)

/** Package-visibility helper kept next to its only caller. */
internal fun PackageManager.isInstalled(packageName: String): Boolean =
    runCatching { getPackageInfo(packageName, 0) }.isSuccess
