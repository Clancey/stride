package io.stride.spikes.appstore

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where bytes come from. Injectable so the download path is testable without a network. */
fun interface ArtifactFetcher {
    /**
     * Opens a stream for [url]. Implementations must fail rather than follow a redirect off `https`.
     *
     * @throws IOException on any transport failure.
     */
    fun open(url: String): InputStream
}

/** How far a download has got, for the launcher's progress row. */
data class DownloadProgress(val bytes: Long, val totalBytes: Long) {
    val fraction: Double get() = if (totalBytes > 0) bytes.toDouble() / totalBytes else 0.0
}

/** A download that did not produce a file we are willing to install. */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fetches catalog artifacts into app-private storage and refuses to hand back anything whose bytes
 * do not match the digest the catalog promised.
 *
 * Three deliberate constraints:
 *
 * - **App-private cache only.** Never `/sdcard`. A world-readable staging directory means any other
 *   app on the console can swap the APK between verification and install, which is exactly the
 *   window the digest check exists to close.
 * - **No resume, no partial reuse.** A failed download is deleted, not continued. Resume logic is
 *   another place for a half-written file to survive, and these artifacts are tens of megabytes on
 *   a machine that is plugged into the wall.
 * - **Digest computed while streaming**, so the file is never read twice and a mismatch is known
 *   before anything else touches it.
 */
class ApkDownloader(
    private val cacheDir: File,
    private val fetcher: ArtifactFetcher,
) {

    /**
     * Downloads [entry] and returns the verified file.
     *
     * @param onProgress called on the calling thread, best-effort, for UI.
     * @throws DownloadException if the transport fails, the size disagrees with the catalog, or the
     *   SHA-256 does not match. The partial file is always removed first.
     */
    fun download(entry: CatalogEntry, onProgress: (DownloadProgress) -> Unit = {}): File {
        if (!entry.url.startsWith("https://")) {
            // CatalogManifest already rejects these; re-checked here because this class is the last
            // thing standing between a URL and a file we will ask the user to install.
            throw DownloadException("refusing non-https artifact url for ${entry.packageName}")
        }

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw DownloadException("cannot create staging directory ${cacheDir.absolutePath}")
        }

        val target = stagingFile(entry)
        target.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            fetcher.open(entry.url).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read
                        if (written > entry.sizeBytes) {
                            throw DownloadException(
                                "artifact for ${entry.packageName} is larger than the catalog says " +
                                    "($written > ${entry.sizeBytes})"
                            )
                        }
                        onProgress(DownloadProgress(written, entry.sizeBytes))
                    }
                }
            }
        } catch (e: DownloadException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            target.delete()
            throw DownloadException("download failed for ${entry.packageName}: ${e.message}", e)
        }

        if (written != entry.sizeBytes) {
            target.delete()
            throw DownloadException(
                "artifact for ${entry.packageName} is ${written}B, catalog says ${entry.sizeBytes}B"
            )
        }

        val actual = digest.digest().toHex()
        if (actual != entry.sha256) {
            target.delete()
            throw DownloadException(
                "sha256 mismatch for ${entry.packageName}: expected ${entry.sha256}, got $actual"
            )
        }

        return target
    }

    /** Removes every staged artifact. Called after a successful install and on catalog change. */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** Stable, collision-free name so two versions of one package cannot alias. */
    fun stagingFile(entry: CatalogEntry): File =
        File(cacheDir, "${entry.packageName}-${entry.versionCode}.apk")

    companion object {
        /**
         * The production fetcher. OkHttp is already a dependency (see build.gradle.kts) and gives us
         * redirect handling we can constrain, plus timeouts — a stalled read on a console with no
         * keyboard is indistinguishable from a hang.
         */
        fun httpFetcher(client: OkHttpClient = defaultClient()): ArtifactFetcher = ArtifactFetcher { url ->
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code} for $url")
            }
            // A redirect that landed on cleartext would defeat the https rule the catalog enforces.
            if (!response.request.url.isHttps) {
                response.close()
                throw IOException("redirect left https for $url")
            }
            response.body?.byteStream() ?: throw IOException("empty body for $url")
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
