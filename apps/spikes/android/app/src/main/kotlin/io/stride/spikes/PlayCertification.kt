package io.stride.spikes

import android.content.ContentResolver
import android.net.Uri

/**
 * The Google Services Framework device id, which is what unblocks a sideloaded Play Store.
 *
 * WHY A TREADMILL NEEDS THIS
 * --------------------------
 * `docs/APPSTORE.md` §11 installs Google Play onto a console that shipped as bare AOSP. That works
 * — the Store runs and installs apps — but Play Protect *certification* is a separate thing, and
 * it is not something an install can satisfy. Google certifies a **build**, by fingerprint, when a
 * manufacturer submits it. NordicTrack never submitted this one, because it was never meant to run
 * Play at all. So every console reaches the same wall on first sign-in:
 *
 *     "This device isn't Play Protect certified."
 *
 * The escape hatch Google provides is per-device rather than per-build: register this console's GSF
 * id against a Google account at https://www.google.com/android/uncertified and the account is
 * allowed to use Play on it. Nothing is patched or bypassed — it is Google's own supported route
 * for exactly this case.
 *
 * That makes the id a **setup value every tester needs**, not a debugging curiosity, which is why
 * it is surfaced in the app. The usual way to read it is a "device id" app off the Play Store, and
 * on this console that is circular: the Store is the thing that does not work yet. The other way is
 * `sqlite3` against GSF's private database, which needs root. Reading it here costs one content
 * query and removes the need for both.
 *
 * DECIMAL, NOT HEX
 * ----------------
 * The registration page wants the **decimal** form. Nearly every "device id" app displays hex, and
 * pasting hex is the single most common reason the page rejects an id that is perfectly valid — it
 * reports nothing more useful than a failure. [decimal] is therefore the value to paste and [hex]
 * exists only to cross-check against those apps.
 */
object PlayCertification {

    /**
     * GSF's key/value provider. Not a documented API, but a stable one: it is how Play itself and
     * every device-id tool have read this value for a decade. A missing provider is the ordinary
     * case on a console with no Google apps yet, not an error worth reporting loudly.
     *
     * Held as a string and parsed at the call site rather than as a `Uri` field: `Uri.parse` is a
     * stubbed Android method under JVM unit tests, and building it here would make merely touching
     * this object throw, taking the pure [decimal] and [hex] rules down with it.
     */
    private const val GSERVICES = "content://com.google.android.gsf.gservices"

    private const val KEY_ANDROID_ID = "android_id"

    /**
     * The console's GSF id, or null when GSF is absent, empty, or unreadable.
     *
     * The key travels in `selectionArgs` and the value comes back in column 1 — an unusual contract
     * for a provider, and the reason a "sensible" projection-based query returns nothing.
     *
     * Broad catch on purpose. This runs on the diagnostics path of a launcher that has to keep
     * working on hardware with no Google apps, where the provider is legitimately missing; it can
     * also throw `SecurityException` on a build that guards `READ_GSERVICES` more tightly. A device
     * id that cannot be read is a "not available" line on a screen, never a crash.
     */
    fun androidId(resolver: ContentResolver): String? = try {
        resolver.query(Uri.parse(GSERVICES), null, null, arrayOf(KEY_ANDROID_ID), null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount >= 2) {
                decimal(cursor.getString(1))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Validates the provider's raw value and returns it in the decimal form the registration page
     * expects.
     *
     * Zero is rejected as absent rather than passed along: GSF writes 0 before it has checked in
     * with Google, and registering a zero id silently accomplishes nothing, which is a far worse
     * outcome for a tester than being told the id is not ready yet.
     */
    internal fun decimal(raw: String?): String? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        return if (value == 0L) null else java.lang.Long.toUnsignedString(value)
    }

    /**
     * The same id as unsigned 16-digit hex, for comparison against device-id apps that show it that
     * way. Never paste this into the registration page.
     */
    internal fun hex(decimalValue: String?): String? {
        val value = decimalValue?.let { java.lang.Long.parseUnsignedLong(it) } ?: return null
        return java.lang.Long.toHexString(value).padStart(16, '0')
    }

    /** Everything the diagnostics screen needs, shaped for the method channel. */
    fun snapshot(resolver: ContentResolver): Map<String, Any?> {
        val id = androidId(resolver)
        return mapOf(
            "gsfAndroidId" to id,
            "gsfAndroidIdHex" to runCatching { hex(id) }.getOrNull(),
            "registrationUrl" to "https://www.google.com/android/uncertified",
        )
    }
}
