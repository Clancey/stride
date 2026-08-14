package io.stride.spikes

import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two separate concerns, both worth guarding.
 *
 * [GlassOsCredentials.pick] decides *which* source is used, and its partial cases are the ones that
 * would produce a mixed keypair. The rest of the file checks that the PEMs actually committed to
 * the repo are the ones the code expects — a truncated or re-encoded file would otherwise only
 * surface as a failed TLS handshake on a treadmill.
 */
class GlassOsCredentialsTest {

    private val ca = "ca"
    private val cert = "cert"
    private val key = "key"

    private fun triple() = listOf(ca, cert, key)
    private fun none() = listOf<String?>(null, null, null)

    @Test
    fun `override wins when it is complete`() {
        val (pems, source) = GlassOsCredentials.pick(triple(), listOf("b-ca", "b-cert", "b-key"))!!
        assertEquals(GlassOsCredentials.Source.OVERRIDE, source)
        assertEquals(triple(), pems)
    }

    @Test
    fun `bundle is used when nothing is provisioned`() {
        val (pems, source) = GlassOsCredentials.pick(none(), triple())!!
        assertEquals(GlassOsCredentials.Source.BUNDLED, source)
        assertEquals(triple(), pems)
    }

    @Test
    fun `a partial override never contributes to a mixed set`() {
        // The whole reason pick() exists. Pairing a console's own CA with the bundled client key
        // fails the handshake in a way that looks like broken hardware, so a partial override must
        // be discarded entirely rather than topped up from the bundle.
        val half = listOf(ca, null, null)
        val (pems, source) = GlassOsCredentials.pick(half, listOf("b-ca", "b-cert", "b-key"))!!
        assertEquals(GlassOsCredentials.Source.BUNDLED, source)
        assertEquals(listOf("b-ca", "b-cert", "b-key"), pems)
        assertTrue("no override material may survive", pems.none { it == ca })
    }

    @Test
    fun `a blank file counts as missing`() {
        // An interrupted push leaves a zero-byte PEM. That must fall back, not fail the handshake.
        val blank = listOf(ca, "   ", key)
        assertEquals(GlassOsCredentials.Source.BUNDLED, GlassOsCredentials.pick(blank, triple())!!.second)
    }

    @Test
    fun `no credentials anywhere is null, not a half-built set`() {
        assertNull(GlassOsCredentials.pick(none(), none()))
    }

    @Test
    fun `a stripped bundle still lets an override through`() {
        assertEquals(GlassOsCredentials.Source.OVERRIDE, GlassOsCredentials.pick(triple(), none())!!.second)
    }

    // ---------------------------------------------------------------- the committed PEMs

    private fun asset(name: String) = File("src/main/assets/glassos/$name")

    @Test
    fun `the bundled credentials are present and parse`() {
        val cf = CertificateFactory.getInstance("X.509")
        val caCert = cf.generateCertificate(asset("ca_cert.pem").inputStream()) as X509Certificate
        val client = cf.generateCertificate(asset("client_cert.pem").inputStream()) as X509Certificate
        assertNotNull(caCert)
        assertNotNull(client)

        val der = java.util.Base64.getMimeDecoder().decode(
            asset("client_key.pem").readText()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""),
        )
        // Unencrypted PKCS#8 RSA, which is what the loader assumes. A PKCS#1 or encrypted key
        // would parse nowhere and strand every tester.
        assertEquals("RSA", KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)).algorithm)
    }

    @Test
    fun `the client certificate matches the client id GlassOS is sent`() {
        // GlassOS rejects a client-id header that disagrees with the certificate CN, so these two
        // must not be allowed to drift apart independently.
        val client = CertificateFactory.getInstance("X.509")
            .generateCertificate(asset("client_cert.pem").inputStream()) as X509Certificate
        assertEquals("CN=com.ifit.eriador", client.subjectX500Principal.name)
    }

    @Test
    fun `the client certificate is signed by the bundled CA and both are in date`() {
        val cf = CertificateFactory.getInstance("X.509")
        val caCert = cf.generateCertificate(asset("ca_cert.pem").inputStream()) as X509Certificate
        val client = cf.generateCertificate(asset("client_cert.pem").inputStream()) as X509Certificate

        // Throws if the signature does not verify. The loader pins this CA and nothing else, so a
        // mismatched pair would trust a certificate it can never present.
        client.verify(caCert.publicKey)

        val now = java.util.Date()
        caCert.checkValidity(now)
        client.checkValidity(now)
    }
}
