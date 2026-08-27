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

    enum class Initialization {
        NOT_STARTED,
        LOADING,
        READY,
        FAILED,
    }

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
    private var initializationGeneration = 0L
    private var recheckRequested = false

    @Volatile
    var initialization: Initialization = Initialization.NOT_STARTED
        private set

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

    data class CheckRequest(val generation: Long, val shouldRun: Boolean)
    data class CheckFinish(val finished: Boolean, val shouldRecheck: Boolean)

    @Synchronized
    fun beginCheck(): CheckRequest {
        if (checking) {
            recheckRequested = true
            return CheckRequest(initializationGeneration, shouldRun = false)
        }
        initializationGeneration++
        if (catalog == null) initialization = Initialization.LOADING
        checking = true
        lastError = null
        notifyListeners()
        return CheckRequest(initializationGeneration, shouldRun = true)
    }

    @Synchronized
    fun completeCheck(
        generation: Long,
        catalog: CatalogManifest,
        plan: List<PlanItem>,
    ): Boolean {
        if (generation != initializationGeneration) return false
        this.catalog = catalog
        this.plan = plan
        this.initialization = Initialization.READY
        this.lastError = null
        this.lastCheckElapsedMs = SystemClock.elapsedRealtime()
        this.lastCheckWallMs = System.currentTimeMillis()
        // Drop status rows for packages the catalog no longer mentions, so a stale "failed" cannot
        // outlive the entry it was about.
        val known = plan.map { it.packageName }.toSet()
        statuses.keys.retainAll(known)
        notifyListeners()
        return true
    }

    @Synchronized
    fun finishCheck(generation: Long): CheckFinish {
        if (generation != initializationGeneration || !checking) {
            return CheckFinish(finished = false, shouldRecheck = false)
        }
        checking = false
        val shouldRecheck = recheckRequested
        recheckRequested = false
        notifyListeners()
        return CheckFinish(finished = true, shouldRecheck = shouldRecheck)
    }

    /**
     * Seed from a cached catalog after a process restart, without claiming a check just happened.
     *
     * [checkedAtWallMs] is the wall-clock of the check that produced these bytes, so the launcher
     * says "checked 5 minutes ago" - which is true - instead of "not checked yet". Deliberately
     * leaves [lastCheckElapsedMs] at 0: it is `elapsedRealtime`, which is measured from boot and is
     * meaningless once carried across a reboot, and it gates freshness decisions that must fail
     * towards checking again rather than towards trusting a stale value.
     *
     * Never overwrites a live result.
     */
    @Synchronized
    fun beginInitialization(): Long? {
        if (checking || initialization == Initialization.LOADING || catalog != null) return null
        initializationGeneration++
        initialization = Initialization.LOADING
        lastError = null
        notifyListeners()
        return initializationGeneration
    }

    @Synchronized
    fun restore(
        catalog: CatalogManifest,
        plan: List<PlanItem>,
        checkedAtWallMs: Long,
        generation: Long = initializationGeneration,
    ): Boolean {
        if (generation != initializationGeneration || this.catalog != null) return false
        this.catalog = catalog
        this.plan = plan
        this.initialization = Initialization.READY
        this.lastCheckWallMs = checkedAtWallMs
        notifyListeners()
        return true
    }

    @Synchronized
    fun failInitialization(generation: Long, reason: String): Boolean {
        if (generation != initializationGeneration || catalog != null) return false
        initialization = Initialization.FAILED
        lastError = reason
        notifyListeners()
        return true
    }

    @Synchronized
    fun failCheck(generation: Long, reason: String): Boolean {
        if (generation != initializationGeneration) return false
        initialization = if (catalog == null) Initialization.FAILED else Initialization.READY
        lastError = reason
        lastCheckElapsedMs = SystemClock.elapsedRealtime()
        lastCheckWallMs = System.currentTimeMillis()
        notifyListeners()
        return true
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

    data class Snapshot(
        val initialization: Initialization,
        val catalog: CatalogManifest?,
        val plan: List<PlanItem>,
        val checking: Boolean,
        val lastCheckWallMs: Long,
        val lastError: String?,
        val bundleRun: BundleRun?,
        val statuses: List<PackageStatus>,
        val busy: Boolean,
    )

    /** One coherent publication for MethodChannel clients. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        initialization = initialization,
        catalog = catalog,
        plan = plan,
        checking = checking,
        lastCheckWallMs = lastCheckWallMs,
        lastError = lastError,
        bundleRun = bundleRun,
        statuses = statuses.values.toList(),
        busy = checking || bundleRun?.running == true || statuses.values.any {
            it.stage == Stage.DOWNLOADING ||
                it.stage == Stage.INSTALLING ||
                it.stage == Stage.AWAITING_USER
        },
    )

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
        initialization = Initialization.NOT_STARTED
        initializationGeneration = 0L
        recheckRequested = false
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
