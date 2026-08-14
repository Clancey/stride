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
}
