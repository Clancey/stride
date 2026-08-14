package io.stride.spikes.appstore

import android.os.SystemClock

/**
 * The single in-process source of truth for everything the app store knows.
 *
 * Same reasoning as [io.stride.spikes.WorkoutSession]: the work happens in a foreground service that
 * outlives the Flutter engine, so the state cannot live in Dart. Flutter polls a snapshot of this
 * over the method channel, exactly like every other spike surface does.
 *
 * A singleton object, not a service field, because the install-result `BroadcastReceiver` is a
 * separate component the platform instantiates on its own and it must be able to report into the
 * same state the service is publishing.
 */
object AppstoreState {

    enum class Stage {
        /** Nothing in flight. */
        IDLE,

        /** Bytes are moving. Allowed during a workout — a download draws nothing. */
        DOWNLOADING,

        /** Downloaded and verified, waiting for a moment when installing is permitted. */
        READY,

        /** A `PackageInstaller` session is open. */
        INSTALLING,

        /** The system is showing its confirmation UI, or is waiting for us to raise it. */
        AWAITING_USER,

        INSTALLED,
        FAILED,
    }

    data class PackageStatus(
        val packageName: String,
        val stage: Stage = Stage.IDLE,
        val bytes: Long = 0L,
        val totalBytes: Long = 0L,
        val message: String? = null,
    )

    /**
     * A multi-package install in flight.
     *
     * Held separately from [PackageStatus] because the rider asked for one thing ("install Google
     * Play") and should be told about one thing, even though four confirmations and four sessions
     * are involved. Per-package rows would turn a single decision into four mysteries.
     */
    data class BundleRun(
        val bundleId: String,
        val running: Boolean,
        val message: String?,
        val failed: Boolean = false,
        /** Set once every member landed and the bundle says a reboot is needed to finish. */
        val restartRequired: Boolean = false,
    )

    private val listeners = mutableListOf<() -> Unit>()
    private val statuses = LinkedHashMap<String, PackageStatus>()

    @Volatile
    var bundleRun: BundleRun? = null
        private set

    @Synchronized
    fun bundleProgress(bundleId: String, message: String) {
        bundleRun = BundleRun(bundleId, running = true, message = message)
        notifyListeners()
    }

    @Synchronized
    fun bundleFailed(bundleId: String, message: String) {
        bundleRun = BundleRun(bundleId, running = false, message = message, failed = true)
        notifyListeners()
    }

    @Synchronized
    fun bundleComplete(bundleId: String, restartRequired: Boolean, message: String) {
        bundleRun = BundleRun(
            bundleId = bundleId,
            running = false,
            message = message,
            restartRequired = restartRequired,
        )
        notifyListeners()
    }

    @Synchronized
    fun clearBundleRun() {
        bundleRun = null
        notifyListeners()
    }

    @Volatile
    var catalog: CatalogManifest? = null
        private set

    @Volatile
    var plan: List<PlanItem> = emptyList()
        private set

    @Volatile
    var checking: Boolean = false
        private set

    /** `SystemClock.elapsedRealtime` of the last completed check, or 0. */
    @Volatile
    var lastCheckElapsedMs: Long = 0L
        private set

    /** Wall-clock of the last completed check, for display only. */
    @Volatile
    var lastCheckWallMs: Long = 0L
        private set

    /** Why the last check failed, or null. Kept visible: a store that silently stops is worse. */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun beginCheck() {
        checking = true
        notifyListeners()
    }

    @Synchronized
    fun completeCheck(catalog: CatalogManifest, plan: List<PlanItem>) {
        this.catalog = catalog
        this.plan = plan
        this.checking = false
        this.lastError = null
        this.lastCheckElapsedMs = SystemClock.elapsedRealtime()
        this.lastCheckWallMs = System.currentTimeMillis()
        // Drop status rows for packages the catalog no longer mentions, so a stale "failed" cannot
        // outlive the entry it was about.
        val known = plan.map { it.packageName }.toSet()
        statuses.keys.retainAll(known)
        notifyListeners()
    }

    @Synchronized
    fun failCheck(reason: String) {
        checking = false
        lastError = reason
        lastCheckElapsedMs = SystemClock.elapsedRealtime()
        lastCheckWallMs = System.currentTimeMillis()
        notifyListeners()
    }

    @Synchronized
    fun status(packageName: String): PackageStatus =
        statuses[packageName] ?: PackageStatus(packageName)

    @Synchronized
    fun update(
        packageName: String,
        stage: Stage,
        bytes: Long = 0L,
        totalBytes: Long = 0L,
        message: String? = null,
    ) {
        statuses[packageName] = PackageStatus(packageName, stage, bytes, totalBytes, message)
        notifyListeners()
    }

    @Synchronized
    fun progress(packageName: String, bytes: Long, totalBytes: Long) {
        val current = statuses[packageName]
        statuses[packageName] = PackageStatus(
            packageName = packageName,
            stage = Stage.DOWNLOADING,
            bytes = bytes,
            totalBytes = totalBytes,
            message = current?.message,
        )
        // Deliberately not notifying per chunk: the downloader calls this every 64 KB and the
        // launcher polls anyway. Waking listeners hundreds of times a second buys nothing.
    }

    @Synchronized
    fun clearStatus(packageName: String) {
        statuses.remove(packageName)
        notifyListeners()
    }

    @Synchronized
    fun allStatuses(): List<PackageStatus> = statuses.values.toList()

    /** True while anything is downloading or installing — the service stays foreground for this. */
    @Synchronized
    fun busy(): Boolean = checking || bundleRun?.running == true || statuses.values.any {
        it.stage == Stage.DOWNLOADING || it.stage == Stage.INSTALLING || it.stage == Stage.AWAITING_USER
    }

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Test/reset hook. Not called in production. */
    @Synchronized
    fun reset() {
        statuses.clear()
        catalog = null
        plan = emptyList()
        checking = false
        lastError = null
        lastCheckElapsedMs = 0L
        lastCheckWallMs = 0L
        bundleRun = null
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
