package io.stride.spikes

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The mutual-TLS material needed to talk to GlassOS, loaded at runtime and never shipped.
 *
 * ## Why nothing is bundled
 *
 * GlassOS verifies its clients: connecting with no client certificate fails the TLS handshake with
 * alert 40, and connecting with the wrong one fails with alert 46. Working credentials exist — they
 * are distributed inside other apps — but Stride does not bake anyone's key material into its own
 * binary. Files are read from app-private storage at runtime, so what a user has on their machine
 * is their business and our APK carries no secret.
 *
 * If the files are absent, [load] returns null, [MachineLink] stays disconnected, and every reading
 * renders as "Not measured". That is the correct behaviour, not a degraded one.
 *
 * For development, push them in:
 * ```
 * adb shell run-as io.stride.spikes mkdir -p files/glassos
 * adb push ca_cert.pem   /data/local/tmp/ && adb shell run-as io.stride.spikes \
 *     cp /data/local/tmp/ca_cert.pem files/glassos/
 * ```
 *
 * ## Why hostname verification is switched off, and why that is not a hole
 *
 * The console's server certificate is `CN=localhost` with **no subjectAltName**. Modern TLS stacks
 * reject CN-only certificates outright, so a default client cannot connect at all.
 *
 * What we do instead is verify the certificate *chain* against the pinned CA below and skip only
 * the hostname match. That is a far narrower exception than it sounds: the endpoint is
 * `127.0.0.1`, so the connection cannot leave the device, and an attacker able to bind loopback on
 * the console already has code execution on it. What we refuse to do is the usual shortcut of
 * trusting all certificates, which would accept literally any server.
 */
object GlassOsCredentials {

    /** Where the PEMs live, relative to the app's private files directory. */
    private const val DIR = "glassos"

    class Material(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    /** True when credentials are present, without doing the work of parsing them. */
    fun present(context: Context): Boolean =
        names().all { File(File(context.filesDir, DIR), it).exists() }

    private fun names() = listOf("ca_cert.pem", "client_cert.pem", "client_key.pem")

    /**
     * Build the TLS material, or null if the credentials are missing or unreadable.
     *
     * Never throws. A malformed certificate must leave Stride disconnected and honest, not crash a
     * service running on a console that has no Back button.
     */
    fun load(context: Context): Material? {
        return try {
            val dir = File(context.filesDir, DIR)
            val caPem = File(dir, "ca_cert.pem").takeIf { it.exists() }?.readText() ?: return null
            val certPem = File(dir, "client_cert.pem").takeIf { it.exists() }?.readText() ?: return null
            val keyPem = File(dir, "client_key.pem").takeIf { it.exists() }?.readText() ?: return null

            val cf = CertificateFactory.getInstance("X.509")
            val ca = cf.generateCertificate(caPem.byteInputStream()) as X509Certificate
            val clientCert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate

            // Trust exactly one CA — the console's — rather than the system roots. A public root
            // has no business signing anything we would accept here.
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("glassos-ca", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trustStore) }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return null

            // The client key is an unencrypted PKCS#8 RSA key, so it needs no passphrase. The
            // in-memory keystore password is not protecting anything and is not a secret.
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der(keyPem)))
            val password = CharArray(0)
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("glassos-client", key, password, arrayOf(clientCert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, password) }

            val ssl = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
            }
            Material(ssl.socketFactory, trustManager)
        } catch (t: Throwable) {
            null
        }
    }

    /** Strip PEM armour and decode the base64 body. */
    private fun der(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return Base64.decode(body, Base64.DEFAULT)
    }
}
