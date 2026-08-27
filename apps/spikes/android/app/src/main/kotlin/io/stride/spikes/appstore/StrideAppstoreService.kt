package io.stride.spikes.appstore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
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
    private val workoutListener = WorkoutSession.Listener { state, _ ->
        if (state == WorkoutSession.State.IDLE) {
            worker.execute { runCatching { installHeldItems() } }
        }
    }

    private val installResults = InstallResultReceiver()

    /**
     * Whether the last [enterForeground] call returned without throwing. Not a cache of the
     * platform's own view - see [enterForeground] for why it is only ever used to decide whether to
     * *retry*, never to decide that a retry is unnecessary.
     */
    @Volatile
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        active = this
        // First, before anything below can throw, and its failure deliberately does not stop us
        // here: see enterForeground and onStartCommand.
        enterForeground()
        WorkoutSession.addListener(workoutListener)
        // Registered here rather than in the manifest: this firmware silently drops background
        // broadcasts to manifest receivers of an O+ app, which stranded every install at
        // STATUS_PENDING_USER_ACTION with no dialog and no error. See ApkInstaller.statusSender.
        // The service outlives every install it starts, so a runtime registration is not a
        // narrower guarantee than a manifest one - it is the same guarantee, actually delivered.
        ContextCompat.registerReceiver(
            this,
            installResults,
            IntentFilter(ApkInstaller.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start delivered to a service that never made it into the foreground is answered here,
        // because this is the first moment after onCreate at which we can do anything about it. A
        // start that arrives at a service which is already foreground needs nothing: the platform
        // clears its own "you owe me a startForeground()" flag for free in that case
        // (ActiveServices.sendServiceArgsLocked, "Service already foreground; no new timeout").
        if (!foreground && !enterForeground()) {
            // Loud, because this is a different bug from the one this method guards against.
            // Reaching here means startForeground() itself refused, not that we were too slow.
            Log.e(TAG, "no foreground notification after two attempts; doing no work")
            if (intent?.action == ACTION_CHECK || intent?.action == null) {
                val generation = intent?.checkGeneration() ?: AppstoreState.beginCheck()
                AppstoreState.failCheck(generation, "update service could not start")
            }
            // Safe to stop, and worth doing. If we arrived by the deadline-free route there is no
            // promise outstanding, so this is a clean exit. If we arrived by the timed route then
            // the platform is going to tear us down and crash the process within ten seconds either
            // way, and starting a download first would only add an orphaned staging file to it.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_INSTALL -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                if (packageName != null) {
                    // A prompt this package already raised, that the user missed or dismissed, is
                    // re-raised rather than restarted: the session is still open, so downloading
                    // and staging it all over again would be work with no visible difference.
                    if (!Confirmations.reshow(this, packageName)) {
                        worker.execute { installNow(packageName) }
                    }
                }
            }

            ACTION_INSTALL_BUNDLE -> {
                val bundleId = intent.getStringExtra(EXTRA_BUNDLE)
                if (bundleId != null) worker.execute { advanceBundle(bundleId) }
            }

            else -> {
                val generation = intent?.checkGeneration() ?: AppstoreState.beginCheck()
                worker.execute { check(generation) }
            }
        }
        // START_STICKY would have the platform restart us with a null intent after a kill, which
        // would silently turn a one-shot install request into a catalog check. That is harmless but
        // confusing; the periodic worker is what guarantees liveness, not stickiness.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WorkoutSession.removeListener(workoutListener)
        runCatching { unregisterReceiver(installResults) }
        // Only clear the shared handle if it still points at us. START_NOT_STICKY plus stopSelf
        // means a check arriving as an ACTION_STOP tears us down can construct the replacement
        // before this runs, and nulling it then would leave `isRunning` reporting false about a
        // live service - which `stop` now relies on to avoid creating one just to stop it.
        if (active === this) active = null
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ check

    /** Fetch, parse, classify, then act on whatever the plan says is safe to act on. */
    private fun check(generation: Long) {
        recordCheckStarted(this)
        val url = catalogUrl(this)
        val body = try {
            fetchCatalog(url)
        } catch (e: Exception) {
            AppstoreState.failCheck(generation, "could not reach the catalog: ${e.message}")
            Log.w(TAG, "catalog fetch failed from $url", e)
            return
        }

        val catalog = try {
            CatalogManifest.parse(body)
        } catch (e: CatalogFormatException) {
            AppstoreState.failCheck(generation, "catalog is not usable: ${e.message}")
            Log.w(TAG, "catalog rejected", e)
            return
        }

        val plan = UpdatePlan.compute(catalog, installedApps(), deviceProfile())
        if (!AppstoreState.completeCheck(generation, catalog, plan)) return
        // Only cache what parsed. Storing the raw body first would let one bad deploy poison every
        // subsequent start with a catalog we already know we cannot read.
        cacheCatalog(this, body)

        // Third-party updates proceed on their own; Stride's own upgrade waits for a tap.
        UpdatePlan.backgroundInstallable(plan, catalog.bundledPackages).forEach { item ->
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

    // ----------------------------------------------------------------- bundle

    /**
     * Installs the next missing member of a bundle, one per call.
     *
     * Each member is a separate `PackageInstaller` session with its own confirmation, and the result
     * arrives asynchronously at [InstallResultReceiver] - so this cannot be a loop. It installs one
     * package and returns; [onInstallSettled] calls it again when that one lands. What makes the
     * chain safe to resume is that the next step is recomputed from what is *installed right now*
     * rather than from a stored cursor, so a run interrupted by a dismissed dialog, a workout, or
     * process death picks up exactly where the device actually is.
     */
    private fun advanceBundle(bundleId: String) {
        val catalog = AppstoreState.catalog
        if (catalog == null) {
            AppstoreState.bundleFailed(bundleId, "check for updates first")
            return
        }
        val bundle = catalog.bundleFor(bundleId)
        if (bundle == null) {
            AppstoreState.bundleFailed(bundleId, "the catalog no longer offers this")
            return
        }

        val installed = installedApps().map { it.packageName }.toSet()
        val next = BundlePlan.next(bundle, installed)
        if (next == null) {
            AppstoreState.bundleComplete(
                bundleId = bundleId,
                restartRequired = bundle.restartRequired,
                message = if (bundle.restartRequired) {
                    "${bundle.name} is installed. Restart the console to finish."
                } else {
                    "${bundle.name} is installed."
                },
            )
            val generation = AppstoreState.beginCheck()
            worker.execute { check(generation) }
            return
        }

        val entry = catalog.entryFor(next)
        if (entry == null) {
            // parse() rejects a bundle naming an unknown package, so reaching this means the
            // catalog changed under a run that was already in flight.
            AppstoreState.bundleFailed(bundleId, "the catalog changed while installing")
            return
        }

        val done = BundlePlan.installedCount(bundle, installed)
        AppstoreState.bundleProgress(
            bundleId,
            "Installing ${entry.name} (${done + 1} of ${bundle.packages.size})",
        )
        runCatching { stageAndInstall(entry) }.onFailure {
            AppstoreState.bundleFailed(bundleId, "${entry.name} failed: ${it.message}")
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
            // launcher. Bundle members stay parked too - they are resumed in order by the bundle
            // runner, and installing one here would do it out of sequence.
            .filter { it.role != CatalogRole.STRIDE }
            .filterNot { it.packageName in (catalog.bundledPackages) }
            .forEach { entry -> runCatching { stageAndInstall(entry) } }
    }

    // ----------------------------------------------------------------- device

    private fun installedApps(): List<InstalledApp> = installedApps(this)

    private fun deviceProfile(): DeviceProfile = deviceProfile(this)

    private fun hasUsableGms(): Boolean = hasUsableGms(this)

    // ----------------------------------------------------------- foreground

    /**
     * Put this service in the foreground. Returns false rather than throwing.
     *
     * WHY IT IS SHAPED LIKE THIS (issue #26)
     *
     * The platform's rule is not "be a foreground service"; it is "if someone called
     * [Context.startForegroundService] for you, `startForeground()` must run within ten seconds".
     * Miss that and `ActiveServices` brings the record down with its `fgRequired` flag still set,
     * which throws `RemoteServiceException` on our *main* thread - a fatal crash of the launcher,
     * caused by a background update check. See [ForegroundStart], which is where that promise is
     * avoided in the first place.
     *
     * Two consequences are baked in here:
     *
     * - **It never throws.** An exception escaping `onCreate` is not caught by anything useful; it
     *   becomes `RuntimeException: Unable to create service`, a *different* fatal crash, and one
     *   that also leaves the promise unanswered. A boolean lets the caller decide.
     * - **Failure does not stop the service from `onCreate`.** Calling `stopSelf()` while a
     *   `startForegroundService()` promise is outstanding is, verbatim, the code path that produces
     *   the crash: "Bringing down service while still waiting for start foreground". The decision
     *   to give up is deferred to [onStartCommand], which runs strictly later.
     *
     * The `foreground` flag it sets is only ever read to decide whether to *retry*. It is never
     * used to conclude that a call is unnecessary, because a successful return is not proof the
     * platform agreed: on API 31+ a `startForeground()` from a restricted state returns normally
     * having quietly done nothing.
     */
    private fun enterForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not enter the foreground", e)
        false
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stride updates",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.setShowBadge(false)
        // Not allowed to abort the notification we are about to build. createNotificationChannel is
        // idempotent, so on every start after the first the channel is already there and a failure
        // here changes nothing at all - whereas letting it propagate would turn a cosmetic problem
        // into the missed startForeground() described above.
        runCatching { nm.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "update notification channel could not be created", it) }
        // Deliberately says nothing about motor control. It used to read "no motor control", which
        // was false: checking a catalog and downloading an APK never touches the machine link, and
        // only the install itself interrupts anything. Telling a rider their controls are gone
        // while they still work teaches them to distrust the one notice that will matter.
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Stride updates")
            .setContentText("Checking for app updates")
            // A framework drawable on purpose. A notification with no valid small icon is rejected
            // by NotificationManagerService, and for a foreground service that rejection is itself
            // fatal ("Bad notification for startForeground"), so this must not depend on a resource
            // that a future refactor could rename out from under it.
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CHECK = "io.stride.spikes.APPSTORE_CHECK"
        const val ACTION_INSTALL = "io.stride.spikes.APPSTORE_INSTALL"
        const val ACTION_STOP = "io.stride.spikes.APPSTORE_STOP"
        const val ACTION_INSTALL_BUNDLE = "io.stride.spikes.APPSTORE_INSTALL_BUNDLE"
        const val EXTRA_PACKAGE = "io.stride.spikes.APPSTORE_PACKAGE"
        const val EXTRA_BUNDLE = "io.stride.spikes.APPSTORE_BUNDLE"
        const val EXTRA_CHECK_GENERATION = "io.stride.spikes.APPSTORE_CHECK_GENERATION"

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
        private const val KEY_LAST_CHECK = "last_check_wall_ms"
        private const val KEY_CATALOG = "last_catalog_json"
        private const val CHANNEL_ID = "stride_spikes_appstore"
        private const val NOTIFICATION_ID = 4322
        private const val TAG = "StrideAppstore"

        /**
         * Google Play Services. Detected to decide whether `requiresGms` entries are offerable —
         * and, since the Google Play bundle exists, installable by this very service.
         */
        private const val GMS_PACKAGE = "com.google.android.gms"

        @Volatile
        private var active: StrideAppstoreService? = null
        private val startup = AppstoreStartup(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "StrideAppstoreStartup").apply { isDaemon = true }
            },
        )

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

        fun check(context: Context) {
            // Publish and invalidate any cache restore before asking Android to enqueue the service.
            // Waiting for onStartCommand leaves a window where stale cache can win after a manual
            // check has already been requested.
            val generation = AppstoreState.beginCheck()
            try {
                start(context, ACTION_CHECK) {
                    it.putExtra(EXTRA_CHECK_GENERATION, generation)
                }
            } catch (e: Exception) {
                AppstoreState.failCheck(
                    generation,
                    "update service could not start: ${e.message}",
                )
                throw e
            }
        }

        /**
         * Check, but only if the last one is old enough. Called on every launcher start.
         *
         * The timestamp is persisted rather than kept in [AppstoreState], which lives only as long
         * as the process: a launcher restart would otherwise always look like "never checked" and
         * this would fetch the catalog every single time.
         *
         * When the guard says "too soon", restore the last result from disk instead of doing
         * nothing. Persisting only the timestamp made a restart within the interval suppress the
         * check *and* leave the launcher with an empty [AppstoreState] - so the store read "not
         * checked yet" and the header badge showed nothing, hiding a pending update until the rider
         * happened to tap Check now. Suppressing the network call is right; forgetting what it
         * returned is not.
         */
        fun checkOnStart(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            val appContext = context.applicationContext
            val shouldCheck = UpdatePlan.shouldCheckOnStart(last, System.currentTimeMillis())
            if (shouldCheck) {
                startup.initialize(
                    shouldCheck = true,
                    check = { check(appContext) },
                    restore = {},
                    // check() has already published the generation-bound failure. This boundary
                    // keeps a platform start exception from escaping MainActivity startup.
                    onFailure = { error -> Log.w(TAG, "startup check could not be enqueued", error) },
                )
                return
            }

            val generation = AppstoreState.beginInitialization()
            startup.initialize(
                shouldCheck = false,
                check = {},
                restore = { restoreCached(appContext, generation) },
                onFailure = { error ->
                    Log.w(TAG, "cached catalog initialization failed", error)
                    AppstoreState.failInitialization(
                        generation,
                        if (error is IllegalStateException) {
                            "No successful catalog is cached; check again when network is available."
                        } else {
                            "cached catalog is not usable: " +
                                (error.message ?: error.javaClass.simpleName)
                        },
                    )
                },
            )
        }

        /**
         * Seed [AppstoreState] from the last catalog this console successfully parsed.
         *
         * The plan is *recomputed* rather than cached: what is installed can change between
         * processes - by our own installer, by adb, by an uninstall - and a stale plan would offer
         * an update for something already updated. Only the catalog bytes are worth keeping; the
         * verdict is cheap and must be current.
         *
         * Never overwrites a live result. A missing or bad cache is published explicitly so the
         * launcher cannot confuse an unanswered initialization with an answered empty catalog.
         */
        internal fun restoreCached(context: Context, generation: Long) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val body = prefs.getString(KEY_CATALOG, null)
                ?: throw IllegalStateException("no cached catalog is available")
            val checkedAt = prefs.getLong(KEY_LAST_CHECK, 0L)
            val catalog = CatalogManifest.parse(body)
            val plan = UpdatePlan.compute(catalog, installedApps(context), deviceProfile(context))
            AppstoreState.restore(catalog, plan, checkedAt, generation)
        }

        /** Stored so a launcher restart can show the last known state without hitting the network. */
        internal fun cacheCatalog(context: Context, body: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_CATALOG, body) }
        }

        internal fun installedApps(context: Context): List<InstalledApp> {
            val pm = context.packageManager
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

        internal fun deviceProfile(context: Context): DeviceProfile = DeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            selfPackage = context.packageName,
            hasGms = hasUsableGms(context),
        )

        /**
         * Whether Google Play Services is present and enabled.
         *
         * This used to additionally require `FLAG_SYSTEM`, on the reasoning that Play Services must
         * live in `/system/priv-app` to hold its signature-level permissions, so a user-space
         * sideload would install and then be inert. That was tested on this hardware on 2026-08-14
         * and is **not** what happens: an ordinary `PackageInstaller` sideload of GSF Login, GSF,
         * GMS Core (base plus its density split) and the Play Store, installed in that order and
         * followed by a reboot, gives a Play Store that signs in and installs apps. See
         * `docs/APPSTORE.md` §11.
         *
         * So the check is now presence and enablement, which is what callers actually meant.
         * Requiring `FLAG_SYSTEM` would report false on a console where Play demonstrably works, and
         * mark every `requiresGms` app ineligible on the one device that had just earned them.
         */
        internal fun hasUsableGms(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(GMS_PACKAGE, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        /**
         * Stamped when a check *begins*, not when it succeeds. A console with no network would
         * otherwise retry on every launch, which is the same hammering the guard exists to prevent.
         */
        internal fun recordCheckStarted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        }

        fun install(context: Context, packageName: String) =
            start(context, ACTION_INSTALL) { it.putExtra(EXTRA_PACKAGE, packageName) }

        fun installBundle(context: Context, bundleId: String) =
            start(context, ACTION_INSTALL_BUNDLE) { it.putExtra(EXTRA_BUNDLE, bundleId) }

        /**
         * Stop the service, if there is one.
         *
         * The `isRunning` guard is not an optimisation. Without it a "stop" is delivered by
         * *starting* the service, so a stop aimed at a service that is not there creates one purely
         * in order to tear it down again a moment later - a start-then-stop, which is the second
         * way to reach the crash in [ForegroundStart]. There is a race here in principle (the
         * service can die between the check and the start) and it is deliberately not closed with a
         * lock: losing it costs one redundant service lifetime, and `onStartCommand` puts us in the
         * foreground before it honours `ACTION_STOP` precisely so that losing it stays harmless.
         */
        fun stop(context: Context) {
            if (!isRunning()) return
            start(context, ACTION_STOP)
        }

        /**
         * Ask the platform to start the service - without a ten-second deadline attached whenever
         * that is possible.
         *
         * Read [ForegroundStart] before changing this. The short version: `startForegroundService`
         * is a promise, missing it is a fatal `RemoteServiceException` on the main thread (issue
         * #26), and every caller except [AppstoreWorker] is already in the foreground and therefore
         * has no need to make one.
         *
         * [AppstoreWorker] is the exception and keeps the promise on purpose: it is a `WorkManager`
         * job that runs when Stride is nothing but a process, where the plain start is refused
         * outright. Do not "tidy" that fallback away or wrap it in a `runCatching` - the periodic
         * update check is the thing that stops working, silently, on the consoles nobody is
         * looking at.
         */
        private fun start(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, StrideAppstoreService::class.java).setAction(action)
            configure(intent)
            ForegroundStart.run(
                plain = { context.startService(intent) },
                promised = { context.startForegroundService(intent) },
            )
        }

        /**
         * Called from [InstallResultReceiver] once an install has succeeded or failed for good.
         * Drops the staged artifact — tens of megabytes we have no further use for — and refreshes
         * the plan so the launcher stops offering something already installed.
         */
        fun onInstallSettled(context: Context, packageName: String, success: Boolean) {
            val entry = AppstoreState.catalog?.entryFor(packageName) ?: return
            runCatching { File(stagingDir(context), "$packageName-${entry.versionCode}.apk").delete() }

            // A bundle in flight advances itself rather than waiting for the next catalog check:
            // four sequential installs separated by a network round trip each is a long time to
            // stand on a treadmill watching nothing happen.
            val run = AppstoreState.bundleRun
            if (run != null && run.running) {
                val bundle = AppstoreState.catalog?.bundleFor(run.bundleId)
                if (bundle != null && packageName in bundle.packages) {
                    if (success) {
                        start(context, ACTION_INSTALL_BUNDLE) {
                            it.putExtra(EXTRA_BUNDLE, run.bundleId)
                        }
                    } else {
                        // Stop the whole sequence. The later members depend on the earlier ones, so
                        // carrying on past a failure just produces more failures and buries the one
                        // that mattered.
                        AppstoreState.bundleFailed(
                            run.bundleId,
                            "${entry.name} did not install, so the rest was not attempted",
                        )
                    }
                    return
                }
            }

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

private fun Intent.checkGeneration(): Long? =
    getLongExtra(StrideAppstoreService.EXTRA_CHECK_GENERATION, 0L).takeIf { it > 0L }
