package io.stride.spikes.appstore

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Stride app catalog: the only document the console parses in order to learn that a newer APK
 * exists.
 *
 * This is deliberately a *strict* parser. Everything it accepts ends up being handed to
 * [android.content.pm.PackageInstaller], so a permissive "best effort" read of a malformed or
 * hostile manifest is a way to install an arbitrary APK on a machine with a motor. The rules:
 *
 * - Unknown [schema] versions are rejected wholesale rather than partially understood. A future
 *   manifest may add fields whose *absence of meaning* to an old client is unsafe (a "this build is
 *   revoked" flag, say), so an old client must refuse rather than ignore it.
 * - Artifact URLs must be `https`. targetSdk 28 still permits cleartext at the platform level
 *   (see the build.gradle.kts note on why the target stays at 28), so this is enforced here rather
 *   than assumed from network-security config.
 * - `sha256` and `signerSha256` are mandatory. The transport is not the integrity story; the digests
 *   are. See [ApkVerifier].
 *
 * Parsing lives in plain JVM code with no Android imports beyond `org.json` so the rejection cases
 * can be unit tested, which is where they are actually specified.
 */
data class CatalogManifest(
    val schema: Int,
    val generated: String?,
    val apps: List<CatalogEntry>,
) {
    /** The Stride build itself, if the catalog offers one. At most one entry may claim this role. */
    val strideEntry: CatalogEntry? get() = apps.firstOrNull { it.role == CatalogRole.STRIDE }

    fun entryFor(packageName: String): CatalogEntry? =
        apps.firstOrNull { it.packageName == packageName }

    companion object {
        /** The only schema version this build understands. */
        const val SUPPORTED_SCHEMA = 1

        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        /**
         * Parses and validates a catalog document.
         *
         * @throws CatalogFormatException on anything that is not a manifest this client fully
         *   understands. There is no partial success.
         */
        @JvmStatic
        fun parse(json: String): CatalogManifest {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw CatalogFormatException("catalog is not a JSON object", e)
            }

            if (!root.has("schema")) throw CatalogFormatException("catalog has no schema field")
            val schema = root.optInt("schema", -1)
            if (schema != SUPPORTED_SCHEMA) {
                throw CatalogFormatException(
                    "unsupported catalog schema $schema (this build understands $SUPPORTED_SCHEMA)"
                )
            }

            val appsJson: JSONArray = root.optJSONArray("apps")
                ?: throw CatalogFormatException("catalog has no apps array")

            val apps = ArrayList<CatalogEntry>(appsJson.length())
            for (i in 0 until appsJson.length()) {
                val obj = appsJson.optJSONObject(i)
                    ?: throw CatalogFormatException("apps[$i] is not an object")
                apps.add(parseEntry(obj, i))
            }

            val duplicate = apps.groupBy { it.packageName }.entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) {
                throw CatalogFormatException("duplicate entry for package ${duplicate.key}")
            }
            if (apps.count { it.role == CatalogRole.STRIDE } > 1) {
                throw CatalogFormatException("catalog declares more than one stride entry")
            }

            return CatalogManifest(
                schema = schema,
                generated = root.optString("generated").takeIf { it.isNotEmpty() },
                apps = apps,
            )
        }

        private fun parseEntry(obj: JSONObject, index: Int): CatalogEntry {
            fun required(field: String): String {
                val value = obj.optString(field)
                if (value.isEmpty()) throw CatalogFormatException("apps[$index] is missing $field")
                return value
            }

            val packageName = required("package")
            if (!isPlausiblePackageName(packageName)) {
                throw CatalogFormatException(
                    "apps[$index] has an implausible package name '$packageName'"
                )
            }

            val roleName = obj.optString("role").ifEmpty { CatalogRole.APP.wire }
            val role = CatalogRole.fromWire(roleName)
                ?: throw CatalogFormatException("apps[$index] has unknown role '$roleName'")

            val url = required("url")
            if (!url.startsWith("https://")) {
                throw CatalogFormatException("apps[$index] url is not https: $url")
            }

            val versionCode = obj.optLong("versionCode", -1L)
            if (versionCode <= 0L) {
                throw CatalogFormatException("apps[$index] has a non-positive versionCode")
            }

            val sizeBytes = obj.optLong("sizeBytes", -1L)
            if (sizeBytes <= 0L) {
                throw CatalogFormatException("apps[$index] has a non-positive sizeBytes")
            }

            val sha256 = required("sha256").lowercase()
            if (!HEX_64.matches(sha256)) {
                throw CatalogFormatException("apps[$index] sha256 is not a 64-character hex digest")
            }
            val signerSha256 = required("signerSha256").lowercase()
            if (!HEX_64.matches(signerSha256)) {
                throw CatalogFormatException(
                    "apps[$index] signerSha256 is not a 64-character hex digest"
                )
            }

            val abisJson = obj.optJSONArray("abis")
            val abis = buildList {
                if (abisJson != null) {
                    for (i in 0 until abisJson.length()) {
                        val abi = abisJson.optString(i)
                        if (abi.isNotEmpty()) add(abi)
                    }
                }
            }

            // Split APKs (an "XAPK"/app bundle): the base is `url` above, and these are the config
            // splits that must be installed *in the same session* as it. An app whose native code
            // lives in a config.<abi> split installs happily without it and then crashes on launch,
            // so a partial install is worse than no install - hence every split is validated here
            // and any bad one rejects the whole entry.
            val splitsJson = obj.optJSONArray("splits")
            val splits = buildList {
                if (splitsJson != null) {
                    for (i in 0 until splitsJson.length()) {
                        val s = splitsJson.optJSONObject(i)
                            ?: throw CatalogFormatException("apps[$index] splits[$i] is not an object")
                        val sUrl = s.optString("url")
                        if (!sUrl.startsWith("https://")) {
                            throw CatalogFormatException("apps[$index] splits[$i] url is not https")
                        }
                        val sSha = s.optString("sha256").lowercase()
                        if (!HEX_64.matches(sSha)) {
                            throw CatalogFormatException(
                                "apps[$index] splits[$i] sha256 is not a 64-character hex digest"
                            )
                        }
                        val sSize = s.optLong("sizeBytes", -1L)
                        if (sSize <= 0L) {
                            throw CatalogFormatException(
                                "apps[$index] splits[$i] has a non-positive sizeBytes"
                            )
                        }
                        add(SplitArtifact(name = s.optString("name").ifEmpty { "split$i" },
                            url = sUrl, sha256 = sSha, sizeBytes = sSize))
                    }
                }
            }

            return CatalogEntry(
                packageName = packageName,
                role = role,
                name = obj.optString("name").ifEmpty { packageName },
                versionCode = versionCode,
                versionName = obj.optString("versionName").ifEmpty { versionCode.toString() },
                minSdk = obj.optInt("minSdk", 0),
                abis = abis,
                url = url,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                signerSha256 = signerSha256,
                releaseNotesUrl = obj.optString("releaseNotesUrl").takeIf { it.isNotEmpty() },
                requiresGms = obj.optBoolean("requiresGms", false),
                splits = splits,
            )
        }

        /**
         * A cheap sanity check, not the platform's own rule. Its job is to stop obvious junk (a URL,
         * a path, an empty segment) from reaching `PackageManager`, not to re-implement Android's
         * package-name grammar.
         */
        private fun isPlausiblePackageName(value: String): Boolean {
            if (!value.contains('.')) return false
            return value.split('.').all { segment ->
                segment.isNotEmpty() &&
                    Character.isJavaIdentifierStart(segment.first()) &&
                    segment.all { Character.isJavaIdentifierPart(it) }
            }
        }
    }
}

/** What a catalog entry *is*, which decides how aggressively Stride may act on it. */
enum class CatalogRole(val wire: String) {
    /** Stride itself. Self-update: always prompted, never automatic. See [UpdatePlan]. */
    STRIDE("stride"),

    /** An ordinary third-party app the rider has pinned or wants. */
    APP("app"),
    ;

    companion object {
        fun fromWire(value: String): CatalogRole? = values().firstOrNull { it.wire == value }
    }
}

/** One installable artifact, already validated by [CatalogManifest.parse]. */
data class CatalogEntry(
    val packageName: String,
    val role: CatalogRole,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val abis: List<String>,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val signerSha256: String,
    val releaseNotesUrl: String?,
    /**
     * True when this app is useless without Google Play Services.
     *
     * This console ships without GMS, and it cannot be given GMS: Play Services and the Play Store
     * are privileged apps that must live in `/system/priv-app` with a `privapp-permissions`
     * allowlist entry, which needs root or a custom recovery this hardware does not have. An
     * unprivileged sideload of them installs and then does nothing.
     *
     * So the catalog says so up front and the client marks the entry ineligible with a reason,
     * rather than installing something that will greet the rider with a sign-in loop.
     */
    val requiresGms: Boolean = false,
    /**
     * Config splits that must be installed together with [url] in a single session.
     *
     * Empty for an ordinary single-APK app. When it is not empty, [url] is the *base* APK and
     * these carry the native libraries, resources and language assets Android selected for this
     * device. Installing the base alone produces an app that appears installed and then dies on
     * launch with a missing-native-library error, which is why [totalBytes] and the installer
     * treat the set as one indivisible artifact.
     */
    val splits: List<SplitArtifact> = emptyList(),
) {
    /** Every artifact that has to be downloaded, base first. */
    val allArtifacts: List<SplitArtifact>
        get() = buildList {
            add(SplitArtifact(name = "base", url = url, sha256 = sha256, sizeBytes = sizeBytes))
            addAll(splits)
        }

    /** What the rider actually waits for, so progress and size reflect the whole install. */
    val totalBytes: Long get() = sizeBytes + splits.sumOf { it.sizeBytes }
}

/**
 * One part of a split install. [name] is only for logging and progress; Android derives the real
 * split identity from the APK's own manifest, not from the filename we give it.
 */
data class SplitArtifact(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** A manifest this client will not act on. Always fatal for the whole document. */
class CatalogFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
