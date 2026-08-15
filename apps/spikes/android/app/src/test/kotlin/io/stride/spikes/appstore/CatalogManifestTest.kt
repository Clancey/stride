package io.stride.spikes.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog parser is the boundary between "a file on the internet" and "an APK we ask the user to
 * install on a treadmill". Its *rejections* are the specification, so they are pinned here rather
 * than discovered on hardware.
 */
class CatalogManifestTest {

    private val digest = "a".repeat(64)
    private val signer = "b".repeat(64)

    private fun entryJson(
        pkg: String = "com.example.app",
        role: String = "app",
        versionCode: Long = 7,
        url: String = "https://example.test/app.apk",
        size: Long = 1024,
        sha: String = digest,
        signerSha: String = signer,
        extra: String = "",
    ): String = """
        {
          "package": "$pkg",
          "role": "$role",
          "name": "Example",
          "versionCode": $versionCode,
          "versionName": "1.2.3",
          "minSdk": 26,
          "abis": ["arm64-v8a"],
          "url": "$url",
          "sizeBytes": $size,
          "sha256": "$sha",
          "signerSha256": "$signerSha"$extra
        }
    """.trimIndent()

    private fun catalog(vararg entries: String): String =
        """{"schema":1,"generated":"2026-08-14T00:00:00Z","apps":[${entries.joinToString(",")}]}"""

    @Test
    fun `parses a well formed catalog`() {
        val manifest = CatalogManifest.parse(catalog(entryJson()))
        assertEquals(1, manifest.apps.size)
        val entry = manifest.apps.single()
        assertEquals("com.example.app", entry.packageName)
        assertEquals(CatalogRole.APP, entry.role)
        assertEquals(7L, entry.versionCode)
        assertEquals(listOf("arm64-v8a"), entry.abis)
        assertNull(manifest.strideEntry)
    }

    @Test
    fun `role defaults to app when absent`() {
        val json = """{"schema":1,"apps":[{
            "package":"com.example.app","name":"Example","versionCode":1,
            "url":"https://example.test/a.apk","sizeBytes":10,
            "sha256":"$digest","signerSha256":"$signer"}]}"""
        assertEquals(CatalogRole.APP, CatalogManifest.parse(json).apps.single().role)
    }

    @Test
    fun `stride entry is found by role`() {
        val manifest = CatalogManifest.parse(
            catalog(entryJson(pkg = "io.stride.spikes", role = "stride"))
        )
        assertEquals("io.stride.spikes", manifest.strideEntry?.packageName)
    }

    // ------------------------------------------------------------------ rejections

    @Test
    fun `rejects an unknown schema outright`() {
        val error = assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse("""{"schema":2,"apps":[]}""")
        }
        assertTrue(error.message!!.contains("schema"))
    }

    @Test
    fun `rejects a missing schema`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse("""{"apps":[]}""")
        }
    }

    @Test
    fun `rejects a document that is not an object`() {
        assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse("[]") }
    }

    @Test
    fun `rejects a missing apps array`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse("""{"schema":1}""")
        }
    }

    @Test
    fun `rejects a cleartext artifact url`() {
        val error = assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(url = "http://example.test/app.apk")))
        }
        assertTrue(error.message!!.contains("https"))
    }

    @Test
    fun `rejects a missing sha256`() {
        val json = """{"schema":1,"apps":[{
            "package":"com.example.app","versionCode":1,
            "url":"https://example.test/a.apk","sizeBytes":10,
            "signerSha256":"$signer"}]}"""
        assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(json) }
    }

    @Test
    fun `rejects a missing signer digest`() {
        val json = """{"schema":1,"apps":[{
            "package":"com.example.app","versionCode":1,
            "url":"https://example.test/a.apk","sizeBytes":10,
            "sha256":"$digest"}]}"""
        assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(json) }
    }

    @Test
    fun `rejects a truncated digest`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(sha = "abc123")))
        }
    }

    @Test
    fun `rejects a non positive version code`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(versionCode = 0)))
        }
    }

    @Test
    fun `rejects a non positive size`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(size = 0)))
        }
    }

    @Test
    fun `rejects an implausible package name`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(pkg = "../etc/passwd")))
        }
    }

    @Test
    fun `rejects an unknown role`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(role = "system")))
        }
    }

    @Test
    fun `rejects duplicate packages`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalog(entryJson(), entryJson()))
        }
    }

    @Test
    fun `rejects two stride entries`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalog(
                    entryJson(pkg = "io.stride.one", role = "stride"),
                    entryJson(pkg = "io.stride.two", role = "stride"),
                )
            )
        }
    }

    /**
     * Byte-for-byte what the published catalog serves before anything is released into it. An empty
     * catalog is a valid catalog - "nothing to offer yet" is not a parse error - and getting this
     * wrong would fail the very first check every console ever makes.
     */
    @Test
    fun `accepts the published empty catalog`() {
        val manifest = CatalogManifest.parse(
            """
            {
              "schema": 1,
              "generated": "2026-08-14T00:00:00Z",
              "apps": []
            }
            """.trimIndent()
        )

        assertTrue(manifest.apps.isEmpty())
        assertNull(manifest.strideEntry)
        assertNull(manifest.entryFor("io.stride.spikes"))
    }

    // ----------------------------------------------------------------- split APKs (app bundles)

    private val splitSha = "c".repeat(64)

    private fun splitsJson(
        url: String = "https://example.test/config.arm64_v8a.apk",
        sha: String = "c".repeat(64),
        size: Long = 512,
    ): String = ""","splits":[{"name":"config.arm64_v8a","url":"$url","sizeBytes":$size,"sha256":"$sha"}]"""

    @Test
    fun `parses an entry with config splits`() {
        val entry = CatalogManifest.parse(catalog(entryJson(extra = splitsJson()))).apps.single()
        assertEquals(1, entry.splits.size)
        assertEquals("config.arm64_v8a", entry.splits.single().name)
        // The base is always first, so the installer writes it into the session before its splits.
        assertEquals(listOf("base", "config.arm64_v8a"), entry.allArtifacts.map { it.name })
        // Progress and disk pre-allocation must account for the whole bundle, not just the base.
        assertEquals(1024L + 512L, entry.totalBytes)
    }

    @Test
    fun `an optional icon url is carried through`() {
        val extra = ",\"iconUrl\":\"https://example.test/icon.png\""
        val entry = CatalogManifest.parse(catalog(entryJson(extra = extra))).apps.single()
        assertEquals("https://example.test/icon.png", entry.iconUrl)
    }

    @Test
    fun `an entry without an icon url is still valid`() {
        // Icons are decoration. A catalog that predates them, or an app nobody has drawn one for,
        // must still install; the client falls back to a letter tile.
        assertNull(CatalogManifest.parse(catalog(entryJson())).apps.single().iconUrl)
    }

    @Test
    fun `an ordinary entry has no splits and totals its own size`() {
        val entry = CatalogManifest.parse(catalog(entryJson())).apps.single()
        assertTrue(entry.splits.isEmpty())
        assertEquals(listOf("base"), entry.allArtifacts.map { it.name })
        assertEquals(1024L, entry.totalBytes)
    }

    @Test
    fun `rejects a split served over plain http`() {
        // Same rule as the base URL. A split carries native code, so a tampered one is arbitrary
        // code execution just as surely as a tampered base.
        val json = catalog(entryJson(extra = splitsJson(url = "http://example.test/x.apk")))
        val e = assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(json) }
        assertTrue(e.message!!.contains("not https"))
    }

    @Test
    fun `rejects a split with a malformed digest`() {
        val json = catalog(entryJson(extra = splitsJson(sha = "nope")))
        val e = assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(json) }
        assertTrue(e.message!!.contains("sha256"))
    }

    @Test
    fun `rejects a split with a non positive size`() {
        // Size is what bounds the download and pre-allocates the session; zero would make the
        // "larger than the catalog says" guard fire on the first byte.
        val json = catalog(entryJson(extra = splitsJson(size = 0)))
        val e = assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(json) }
        assertTrue(e.message!!.contains("sizeBytes"))
    }

    @Test
    fun `one bad split rejects the whole catalog`() {
        // Deliberately fatal for the document rather than skipping the entry: a half-understood
        // bundle installs an app without its native libraries, which crashes on launch.
        val good = entryJson(pkg = "com.example.good")
        val bad = entryJson(pkg = "com.example.bad", extra = splitsJson(sha = "short"))
        assertThrows(CatalogFormatException::class.java) { CatalogManifest.parse(catalog(good, bad)) }
    }

    // ------------------------------------------------------------------ bundles

    private fun catalogWithBundles(apps: String, bundles: String): String =
        """{"schema":1,"apps":[$apps],"bundles":[$bundles]}"""

    private val twoApps =
        entryJson(pkg = "com.google.android.gsf") + "," + entryJson(pkg = "com.android.vending")

    @Test
    fun `parses a bundle and keeps the catalog's install order`() {
        val manifest = CatalogManifest.parse(
            catalogWithBundles(
                twoApps,
                """{"id":"google-play","name":"Google Play","detail":"Play and services",
                   "restartRequired":true,
                   "packages":["com.google.android.gsf","com.android.vending"]}""",
            )
        )
        val bundle = manifest.bundleFor("google-play")!!
        assertEquals("Google Play", bundle.name)
        assertTrue(bundle.restartRequired)
        assertEquals(
            listOf("com.google.android.gsf", "com.android.vending"),
            bundle.packages,
        )
        assertEquals(bundle.packages.toSet(), manifest.bundledPackages)
    }

    @Test
    fun `a catalog without bundles simply has none`() {
        val manifest = CatalogManifest.parse(catalog(entryJson()))
        assertTrue(manifest.bundles.isEmpty())
        assertTrue(manifest.bundledPackages.isEmpty())
        assertNull(manifest.bundleFor("google-play"))
    }

    @Test
    fun `rejects a bundle naming a package the catalog does not carry`() {
        // The runner installs bundle members by looking them up in the catalog. An unresolvable
        // name would strand a run halfway through, which for Play means a sign-in loop.
        val e = assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalogWithBundles(
                    twoApps,
                    """{"id":"google-play","packages":["com.google.android.gsf","com.nope"]}""",
                )
            )
        }
        assertTrue(e.message!!.contains("com.nope"))
    }

    @Test
    fun `rejects a bundle that lists the same package twice`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalogWithBundles(
                    twoApps,
                    """{"id":"x","packages":["com.android.vending","com.android.vending"]}""",
                )
            )
        }
    }

    @Test
    fun `rejects an empty bundle`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(catalogWithBundles(twoApps, """{"id":"x","packages":[]}"""))
        }
    }

    @Test
    fun `rejects a bundle with no id`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalogWithBundles(twoApps, """{"packages":["com.android.vending"]}""")
            )
        }
    }

    @Test
    fun `rejects two bundles sharing an id`() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalogWithBundles(
                    twoApps,
                    """{"id":"x","packages":["com.android.vending"]},""" +
                        """{"id":"x","packages":["com.google.android.gsf"]}""",
                )
            )
        }
    }

    @Test
    fun `rejects a package claimed by two bundles`() {
        // Which bundle a package belongs to decides whether it is hidden from the store list, so
        // "both" has no sensible answer.
        val e = assertThrows(CatalogFormatException::class.java) {
            CatalogManifest.parse(
                catalogWithBundles(
                    twoApps,
                    """{"id":"a","packages":["com.android.vending"]},""" +
                        """{"id":"b","packages":["com.android.vending"]}""",
                )
            )
        }
        assertTrue(e.message!!.contains("more than one bundle"))
    }

    // ----------------------------------------------------------------- release notes

    private val notesJson = """,
        "releaseNotes": [
          {"versionCode": 8, "versionName": "1.0.7", "date": "2026-08-14", "notes": "Older news"},
          {"versionCode": 12, "versionName": "1.0.11", "date": "2026-08-15", "notes": "Newest news"},
          {"versionCode": 9, "versionName": "1.0.8", "date": "2026-08-14", "notes": "Middle news"}
        ]"""

    private fun entryWithNotes(versionCode: Long = 12) =
        CatalogManifest.parse(catalog(entryJson(versionCode = versionCode, extra = notesJson)))
            .apps.single()

    @Test
    fun `release notes parse newest first regardless of catalog order`() {
        // The console renders them in list order, so ordering is this parser's job rather than a
        // property the generator is trusted to have got right.
        assertEquals(
            listOf(12L, 9L, 8L),
            entryWithNotes().releaseNotes.map { it.versionCode },
        )
    }

    @Test
    fun `an entry with no release notes parses`() {
        // Every catalog published before this field existed, and every third-party app in it.
        assertTrue(CatalogManifest.parse(catalog(entryJson())).apps.single().releaseNotes.isEmpty())
    }

    @Test
    fun `a malformed note is skipped rather than rejecting the catalog`() {
        // Notes change what a rider is told, never what is installed. Throwing here would take the
        // console's only update path down to protect some prose.
        val json = """,
            "releaseNotes": [
              {"versionName": "1.0.9", "notes": "no version code"},
              {"versionCode": 11, "notes": "   "},
              "not an object",
              {"versionCode": 12, "versionName": "1.0.11", "notes": "good"}
            ]"""
        val notes = CatalogManifest.parse(catalog(entryJson(extra = json))).apps.single().releaseNotes
        assertEquals(1, notes.size)
        assertEquals("good", notes.single().notes)
    }

    @Test
    fun `notes newer than the installed version are the ones worth showing`() {
        // The case this exists for: the catalog went from versionCode 9 to 12, so a console on 9
        // is owed 12 and nothing else, while a console on 8 is owed both 9 and 12.
        assertEquals(listOf(12L), entryWithNotes().notesNewerThan(9L).map { it.versionCode })
        assertEquals(listOf(12L, 9L), entryWithNotes().notesNewerThan(8L).map { it.versionCode })
    }

    @Test
    fun `a console already on the newest version is told nothing`() {
        assertTrue(entryWithNotes().notesNewerThan(12L).isEmpty())
    }

    @Test
    fun `notes above the served version are never advertised`() {
        // A catalog serving 9 must not describe 12: the console cannot install it yet, so listing
        // it promises a change the update will not deliver.
        assertEquals(
            listOf(9L),
            entryWithNotes(versionCode = 9).notesNewerThan(8L).map { it.versionCode },
        )
    }

    @Test
    fun `an app that is not installed gets the newest note only`() {
        // A first install has no backlog to catch up on; the whole history would just be noise.
        assertEquals(listOf(12L), entryWithNotes().notesNewerThan(null).map { it.versionCode })
    }
}
