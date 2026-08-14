package io.stride.spikes.appstore

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * What we can learn about an APK file *before* installing it. Deliberately a plain data class so the
 * decision in [ApkVerifier.verify] is pure and unit-testable; reading it off a real file is the
 * Android-specific part and lives in [ApkVerifier.inspect].
 */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 of each signing certificate in the APK, lowercase hex. */
    val signerSha256: List<String>,
)

/** Why a staged file will not be installed. */
enum class VerificationFailure {
    UNREADABLE,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNER_MISMATCH,
}

sealed interface VerificationResult {
    data object Ok : VerificationResult
    data class Rejected(val failure: VerificationFailure, val detail: String) : VerificationResult
}

/**
 * The last gate before [ApkInstaller].
 *
 * The SHA-256 in [ApkDownloader] proves we got the bytes the catalog described. This proves the
 * catalog described the thing it claimed: same package, same version, and — the one that actually
 * matters — signed by the key the catalog names.
 *
 * The signer check is not redundant with Android's own. The platform will refuse to *update* an
 * installed app with a differently-signed APK, which protects packages already on the console. It
 * says nothing about a *first* install, which is precisely the case where a compromised catalog
 * could hand the machine an impostor "Spotify". So Stride checks the signer itself, for every
 * install, and fails closed.
 */
object ApkVerifier {

    /** Pure decision. Everything above this line is I/O; everything below it is policy. */
    fun verify(entry: CatalogEntry, facts: ArchiveFacts?): VerificationResult {
        if (facts == null) {
            return VerificationResult.Rejected(
                VerificationFailure.UNREADABLE,
                "the staged file for ${entry.packageName} is not a readable APK",
            )
        }
        if (facts.packageName != entry.packageName) {
            return VerificationResult.Rejected(
                VerificationFailure.PACKAGE_MISMATCH,
                "staged APK is ${facts.packageName}, catalog promised ${entry.packageName}",
            )
        }
        if (facts.versionCode != entry.versionCode) {
            return VerificationResult.Rejected(
                VerificationFailure.VERSION_MISMATCH,
                "staged APK is version ${facts.versionCode}, catalog promised ${entry.versionCode}",
            )
        }
        // Any one of the APK's signers matching is enough: an app signed by a rotated key set
        // legitimately carries more than one certificate.
        if (facts.signerSha256.none { it.equals(entry.signerSha256, ignoreCase = true) }) {
            return VerificationResult.Rejected(
                VerificationFailure.SIGNER_MISMATCH,
                "staged APK is signed by ${facts.signerSha256.joinToString()}, " +
                    "catalog expects ${entry.signerSha256}",
            )
        }
        return VerificationResult.Ok
    }

    /** Reads [file] with `PackageManager`. Returns null when it is not a parseable APK at all. */
    @SuppressLint("PackageManagerGetSignatures")
    fun inspect(context: Context, file: File): ArchiveFacts? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        } catch (e: Exception) {
            // getPackageArchiveInfo is documented to return null, but a corrupt zip can also throw
            // out of the parser. Either way the answer is "we are not installing this".
            return null
        }

        // The SDK stubs annotate packageName non-null, but a partially-parsed archive can still
        // come back blank. Treat that as unreadable rather than comparing "" against the manifest.
        val packageName = info.packageName.orEmpty()
        if (packageName.isBlank()) return null

        return ArchiveFacts(
            packageName = packageName,
            versionCode = info.versionCodeCompat(),
            signerSha256 = info.signerDigests(),
        )
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signerDigests(): List<String> {
        val raw: Array<out android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = signingInfo
                when {
                    info == null -> null
                    info.hasMultipleSigners() -> info.apkContentsSigners
                    else -> info.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                signatures
            }
        return raw?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
