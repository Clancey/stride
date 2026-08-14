package io.stride.spikes

import android.content.Context
import android.util.Log
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
 * The mutual-TLS material needed to talk to GlassOS.
 *
 * ## Two sources, in priority order
 *
 * 1. **App-private storage** — `filesDir/glassos/{ca_cert,client_cert,client_key}.pem`. If all
 *    three are present they win outright.
 * 2. **Bundled assets** — the same three filenames under `assets/glassos/`, shipped in the APK.
 *
 * The two are never mixed. A console provisioned with its own credentials must not end up pairing
 * someone else's CA with its own key, so [pems] takes a complete set from one source or moves on.
 *
 * ## Why they are bundled now
 *
 * They used to be extracted on-device, on the principle that we would not bake anyone's key
 * material into a distributed binary. That principle cost more than it bought:
 *
 * - The credentials are **not per-device**. The CA is `CN=testca`, carrying OpenSSL's untouched
 *   defaults (`C=AU, ST=Some-State, O=Internet Widgits Pty Ltd`), and the client is
 *   `CN=com.ifit.eriador`. One fixed keypair opens every console of this generation.
 * - They are **already public**. This keypair ships inside multiple distributed apps, so nothing
 *   here is being disclosed that was not already in the open.
 * - They protect nothing belonging to the user. They unlock a loopback service on a treadmill the
 *   user already owns, and grant no access to any remote account.
 *
 * Against that, requiring every tester to source their own certificates meant nobody could run
 * Stride on their own machine, which is the entire point of handing it to testers.
 *
 * The keypair does not originate with any of those apps: it is iFit's own material, generated for
 * the console and read back out of its firmware. Redistributors are not its author, so no upstream
 * project's licence attaches to it and Stride's own licensing is unaffected by carrying it. Only
 * the three PEM files are reused here; no third-party source is vendored.
 *
 * Treat the keypair as public knowledge rather than as a secret. It is committed deliberately; see
 * the exception at the end of `.gitignore`.
 *
 * ## Why hostname verification is switched off, and why that is not a hole
 *
 * The console's server certificate is `CN=localhost` with **no subjectAltName**. Modern TLS stacks
 * reject CN-only certificates outright, so a default client cannot connect at all.
 *
 * What we do instead is verify the certificate *chain* against the pinned CA and skip only the
 * hostname match. That is a far narrower exception than it sounds: the endpoint is `127.0.0.1`, so
 * the connection cannot leave the device, and an attacker able to bind loopback on the console
 * already has code execution on it. What we refuse to do is the usual shortcut of trusting all
 * certificates, which would accept literally any server.
 */
object GlassOsCredentials {

    /** Where the PEMs live: under the app's private files directory, and under `assets/`. */
    private const val DIR = "glassos"

    /** Which of the two sources a set of credentials came from. */
    enum class Source {
        /** Provisioned into app-private storage on this specific console. Overrides the bundle. */
        OVERRIDE,

        /** Shipped in the APK. The path every ordinary install takes. */
        BUNDLED,
    }

    class Material(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
        val source: Source,
    )

    /**
     * True when credentials are available, without doing the work of parsing them.
     *
     * Since the bundle ships with the APK this is now effectively always true; it stays because
     * callers should not have to know that, and a build with a stripped bundle must still report
     * honestly rather than claim a connection it cannot make.
     */
    fun present(context: Context): Boolean = pems(context) != null

    private fun names() = listOf("ca_cert.pem", "client_cert.pem", "client_key.pem")

    private class Pems(val ca: String, val cert: String, val key: String, val source: Source)

    /**
     * Read a complete set of PEMs from the highest-priority source that has all three.
     *
     * Taking all three from one source is the point. A half-provisioned console that mixed its own
     * CA with the bundled key would fail the handshake in a way that looks like a hardware fault.
     */
    private fun pems(context: Context): Pems? {
        val chosen = pick(read(context, Source.OVERRIDE), read(context, Source.BUNDLED))
            ?: return null
        val (contents, source) = chosen
        return Pems(contents[0], contents[1], contents[2], source)
    }

    /**
     * Choose a complete set of credentials, preferring the override.
     *
     * Split out as a pure function because the partial cases are the dangerous ones and they are
     * otherwise unreachable in a JVM test. Each argument is `[ca, cert, key]`, with null for
     * anything missing or unreadable. A source is only usable if it supplies all three; a partial
     * override falls through to the bundle *whole* rather than contributing what it has.
     */
    @JvmStatic
    internal fun pick(
        override: List<String?>,
        bundled: List<String?>,
    ): Pair<List<String>, Source>? {
        complete(override)?.let { return it to Source.OVERRIDE }
        complete(bundled)?.let { return it to Source.BUNDLED }
        return null
    }

    private fun complete(pems: List<String?>): List<String>? {
        if (pems.size != 3) return null
        // Blank is as useless as absent, and a zero-byte PEM is a realistic result of a push that
        // was interrupted. Treat it as missing so we fall back rather than fail the handshake.
        if (pems.any { it.isNullOrBlank() }) return null
        @Suppress("UNCHECKED_CAST")
        return pems as List<String>
    }

    private fun read(context: Context, source: Source): List<String?> = names().map { name ->
        try {
            when (source) {
                Source.OVERRIDE -> File(File(context.filesDir, DIR), name)
                    .takeIf { it.exists() }?.readText()
                Source.BUNDLED -> context.assets.open("$DIR/$name")
                    .use { it.reader().readText() }
            }
        } catch (t: Throwable) {
            // Missing bundled assets is a legitimate configuration (a build with them stripped),
            // and an unreadable override must fall back rather than crash a console with no Back
            // button. Either way: report nothing found and let [pick] decide.
            Log.w("StrideGlassOs", "$source $name unreadable: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Build the TLS material, or null if the credentials are missing or unreadable.
     *
     * Never throws. A malformed certificate must leave Stride disconnected and honest, not crash a
     * service running on a console that has no Back button.
     */
    fun load(context: Context): Material? {
        return try {
            val pems = pems(context) ?: return null
            val caPem = pems.ca
            val certPem = pems.cert
            val keyPem = pems.key

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
            Material(ssl.socketFactory, trustManager, pems.source)
        } catch (t: Throwable) {
            // Never propagate: a malformed certificate must leave Stride disconnected and honest.
            // But it must not be *silent* either — an unexplained blank readout is exactly the
            // thing that wastes an hour on a treadmill. Log the failure class, never the contents.
            Log.w("StrideGlassOs", "credentials unusable: ${t.javaClass.simpleName}: ${t.message}")
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
