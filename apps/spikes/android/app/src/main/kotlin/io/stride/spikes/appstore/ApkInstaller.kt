package io.stride.spikes.appstore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Drives Android's session-based installer.
 *
 * Why `PackageInstaller` and not an `ACTION_VIEW` intent at a `FileProvider` URI: the session API
 * reports *why* an install failed (`EXTRA_STATUS_MESSAGE`, `EXTRA_LEGACY_STATUS`) instead of handing
 * the file to the system UI and hoping. On a console with no keyboard, "it didn't work" with no
 * reason is not a diagnosable state. It also keeps the APK inside app-private storage the whole
 * time — no exported provider, no world-readable staging path.
 *
 * Every install here is user-confirmed. Silent installation needs the app to be device owner or
 * privileged, and the console is provisioned as neither; see docs/APPSTORE.md for why that stays a
 * documented future option rather than a flag.
 */
class ApkInstaller(private val context: Context) {

    /**
     * Opens a session, streams [files] into it, and commits.
     *
     * [files] is keyed by artifact name and must contain the base APK plus every split the entry
     * declares. All parts go into a *single* session on purpose: that is how Android installs an
     * app bundle atomically. Committing them separately would either be rejected or leave the app
     * installed without its native-code split, which looks fine until the rider taps it.
     *
     * Returns the session id on success. The *result* arrives asynchronously at
     * [InstallResultReceiver], which is where status is published — committing only means the
     * platform has taken ownership of the request.
     */
    fun install(entry: CatalogEntry, files: Map<String, File>): Int {
        val artifacts = entry.allArtifacts
        val missing = artifacts.filter { files[it.name] == null }
        require(missing.isEmpty()) {
            // Fail here rather than committing a partial bundle: a split install that silently
            // drops config.<abi> produces an app that crashes on launch with no clue why.
            "cannot install ${entry.packageName}: missing ${missing.joinToString { it.name }}"
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(entry.packageName)
            // Lets the platform pre-allocate and fail early on a full disk rather than halfway
            // through writing 60 MB to a console with a small data partition.
            setSize(entry.totalBytes)
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                artifacts.forEach { artifact ->
                    val file = files.getValue(artifact.name)
                    // Each part needs a distinct name within the session; Android reads the real
                    // split identity out of each APK's manifest, not from this name.
                    session.openWrite(artifact.name, 0, artifact.sizeBytes).use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                session.commit(statusSender(entry, sessionId).intentSender)
            }
        } catch (e: Exception) {
            // Abandon rather than leak a half-written session: they persist across process death and
            // occupy disk until the platform garbage-collects them.
            runCatching { installer.abandonSession(sessionId) }
            AppstoreState.update(
                entry.packageName,
                AppstoreState.Stage.FAILED,
                message = "install session failed: ${e.message}",
            )
            throw e
        }

        AppstoreState.update(entry.packageName, AppstoreState.Stage.INSTALLING)
        return sessionId
    }

    /** Single-artifact convenience for the common, non-bundle case. */
    fun install(entry: CatalogEntry, file: File): Int = install(entry, mapOf("base" to file))

    private fun statusSender(entry: CatalogEntry, sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(ACTION_INSTALL_STATUS)
            .putExtra(EXTRA_PACKAGE, entry.packageName)
            .putExtra(EXTRA_LABEL, entry.name)
            .putExtra(EXTRA_SELF, entry.role == CatalogRole.STRIDE)
        // MUTABLE is required: the platform fills this intent in with its status extras. On API 31+
        // omitting an explicit mutability flag is a hard error; below 31 the flag does not exist.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.stride.spikes.appstore.INSTALL_STATUS"
        const val EXTRA_PACKAGE = "io.stride.spikes.appstore.PACKAGE"
        const val EXTRA_LABEL = "io.stride.spikes.appstore.LABEL"
        const val EXTRA_SELF = "io.stride.spikes.appstore.SELF"

        private const val WRITE_NAME = "stride-artifact"
    }
}

/**
 * Where install outcomes land.
 *
 * `STATUS_PENDING_USER_ACTION` is the normal path for an unprivileged installer: the platform hands
 * back an Intent that must be started to show the confirmation dialog. Starting it needs
 * `FLAG_ACTIVITY_NEW_TASK` because a receiver has no task of its own.
 *
 * SAFETY: that dialog is full-screen and covers the overlay, including the stop control. The service
 * is responsible for never reaching this point while the belt is moving
 * ([UpdatePlan.mayInstallNow]); this receiver re-checks it as a backstop, because a session
 * committed before a workout started could otherwise surface mid-run.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(ApkInstaller.EXTRA_PACKAGE) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    AppstoreState.update(
                        packageName,
                        AppstoreState.Stage.FAILED,
                        message = "the installer asked for confirmation but sent no intent",
                    )
                    return
                }
                if (!StrideAppstoreService.installUiAllowed()) {
                    // Hold it. The plan row stays READY and the user can retry from the launcher
                    // once the session is idle.
                    AppstoreState.update(
                        packageName,
                        AppstoreState.Stage.READY,
                        message = "held until the workout is idle",
                    )
                    return
                }
                AppstoreState.update(packageName, AppstoreState.Stage.AWAITING_USER)
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "could not show install confirmation for $packageName", e)
                    AppstoreState.update(
                        packageName,
                        AppstoreState.Stage.FAILED,
                        message = "could not show the install confirmation: ${e.message}",
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppstoreState.update(packageName, AppstoreState.Stage.INSTALLED)
                StrideAppstoreService.onInstallSettled(context, packageName)
            }

            else -> {
                AppstoreState.update(
                    packageName,
                    AppstoreState.Stage.FAILED,
                    message = describe(status, message),
                )
                StrideAppstoreService.onInstallSettled(context, packageName)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

    /**
     * Readable reasons. `STATUS_FAILURE_*` numbers mean nothing on a treadmill screen, and the two
     * that matter here — a signature mismatch and a blocked install — are the ones a user can
     * actually do something about.
     */
    private fun describe(status: Int, message: String?): String {
        val label = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the device"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "conflicts with the installed copy (usually a different signing key)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "the package is malformed"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
            else -> "install failed"
        }
        return if (message.isNullOrEmpty()) label else "$label ($message)"
    }

    private companion object {
        const val TAG = "StrideAppstore"
    }
}
