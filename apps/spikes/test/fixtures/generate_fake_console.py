#!/usr/bin/env python3
"""Regenerate test/fixtures/fake_console.apk - an adversarial stand-in for the iFit console APK.

This fixture exists to stress the DER/ASN.1 scanner in cert_extractor.dart. It deliberately
includes traps that a naive "first self-signed cert is the CA / first key is the client key"
selector gets wrong:

  * META-INF/CERT.RSA: a PKCS#7 SignedData holding a self-signed APK *signing* certificate.
    A real APK always has one. It is not application data and must never be chosen as the
    GlassOS CA. The scanner must skip META-INF entirely.
  * A decoy self-signed cert + matching RSA key pair (CN=Decoy Widget). It is a complete,
    internally consistent RSA pair, so modulus binding alone finds it. It must be rejected
    because it does not chain to any CA present in the archive.
  * The real GlassOS CA (self-signed) + client cert (CN=com.ifit.dev_app, issued by the CA)
    + the client's RSA private key. This is the one and only confidently selectable set.
  * A compact EC private key (PKCS#8) that encodes its length as 0x81 (long form, one length
    byte). The old scanner only matched 0x30 0x82 and would miss it.
  * A hand-built EC certificate whose outer SEQUENCE length is also encoded as 0x81. Real
    X.509 certs almost always exceed 255 bytes (0x82), so this is synthetic, but it proves
    the scanner accepts a certificate carried in the 0x81 length form.
  * The usual decoys: fake-JPEG wrappers (stored and deflated), a real PNG, random bytes,
    a malformed near-miss (0x30 0x82 with an overrunning length), and an oversized entry.

All key material is generated fresh on every run and is worthless throwaway data.
"""

import os
import random
import shutil
import struct
import subprocess
import tempfile
import zipfile

random.seed(0xC0FFEE)  # deterministic decoy bytes; the keys themselves are still random

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "fake_console.apk")

# A believable JFIF header so the credential blobs sit at a non-zero offset behind fake magic.
JPEG_MAGIC = bytes.fromhex("ffd8ffe000104a46494600010100000100010000")


def sh(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def der(pem_or_der_path, kind):
    """Convert an openssl output file to DER bytes. kind in {cert, rsa_pkcs8, ec_pkcs8}."""
    return open(pem_or_der_path, "rb").read()


def jpeg_wrap(der_bytes):
    return JPEG_MAGIC + der_bytes


# --------------------------------------------------------------------- minimal DER builder

def tlv(tag, content):
    length = len(content)
    if length < 0x80:
        head = bytes([tag, length])
    elif length < 0x100:
        head = bytes([tag, 0x81, length])
    elif length < 0x10000:
        head = bytes([tag, 0x82, length >> 8, length & 0xFF])
    else:
        raise ValueError("length too large for this fixture")
    return head + content


def oid(*nums):
    # Only the specific OIDs below are needed, all with small components, so a simple encoder
    # (first two arcs packed into one byte, remaining arcs base-128) is sufficient.
    body = bytes([nums[0] * 40 + nums[1]])
    for n in nums[2:]:
        if n < 0x80:
            body += bytes([n])
        else:
            stack = []
            stack.append(n & 0x7F)
            n >>= 7
            while n:
                stack.append((n & 0x7F) | 0x80)
                n >>= 7
            body += bytes(reversed(stack))
    return tlv(0x06, body)


def build_tiny_ec_cert():
    """A structurally valid (but not signature-checkable) EC certificate whose outer length
    lands in the 0x81 range. Its SubjectPublicKeyInfo advertises id-ecPublicKey so the
    extractor classifies it as EC and reports that RSA modulus binding is not possible."""
    oid_cn = oid(2, 5, 4, 3)                      # id-at-commonName
    oid_ecdsa_sha256 = oid(1, 2, 840, 10045, 4, 3, 2)   # ecdsa-with-SHA256
    oid_ec_pub = oid(1, 2, 840, 10045, 2, 1)      # id-ecPublicKey
    oid_p256 = oid(1, 2, 840, 10045, 3, 1, 7)     # prime256v1

    def name(cn):
        attr = tlv(0x30, oid_cn + tlv(0x13, cn.encode()))  # AttributeTypeAndValue
        rdn = tlv(0x31, attr)                               # RelativeDistinguishedName SET
        return tlv(0x30, rdn)                               # Name SEQUENCE

    serial = tlv(0x02, b"\x2a")
    sig_alg = tlv(0x30, oid_ecdsa_sha256)
    issuer = name("Tiny EC")
    validity = tlv(0x30, tlv(0x17, b"250101000000Z") + tlv(0x17, b"350101000000Z"))
    subject = name("Tiny EC")
    spki = tlv(
        0x30,
        tlv(0x30, oid_ec_pub + oid_p256)
        + tlv(0x03, b"\x00" + bytes(random.randrange(256) for _ in range(8))),
    )
    tbs = tlv(0x30, serial + sig_alg + issuer + validity + subject + spki)
    sig = tlv(0x03, b"\x00" + bytes(random.randrange(256) for _ in range(8)))
    cert = tlv(0x30, tbs + sig_alg + sig)
    assert cert[1] == 0x81, f"expected 0x81 length form, got {cert[1]:#x} (len {len(cert)})"
    return cert


# --------------------------------------------------------------------- openssl material

def main():
    tmp = tempfile.mkdtemp(prefix="fixgen_", dir=HERE)
    try:
        def p(name):
            return os.path.join(tmp, name)

        # GlassOS CA: self-signed RSA.
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
           "-keyout", p("ca.key"), "-out", p("ca.pem"),
           "-subj", "/CN=GlassOS Test CA", "-days", "3650")

        # Client cert: RSA, issued by the CA, CN matches the real GlassOS client id.
        sh("openssl", "req", "-newkey", "rsa:2048", "-nodes",
           "-keyout", p("client.key"), "-out", p("client.csr"),
           "-subj", "/CN=com.ifit.dev_app")
        sh("openssl", "x509", "-req", "-in", p("client.csr"),
           "-CA", p("ca.pem"), "-CAkey", p("ca.key"), "-CAcreateserial",
           "-out", p("client.pem"), "-days", "3650")

        # Decoy pair: self-signed RSA cert + its own key. Internally consistent, but orphaned
        # (no CA in the archive names it as issuer), so it must not be selected.
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
           "-keyout", p("decoy.key"), "-out", p("decoy.pem"),
           "-subj", "/CN=Decoy Widget", "-days", "3650")

        # APK signing cert: self-signed RSA, wrapped in a PKCS#7 as a real .RSA block would be.
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
           "-keyout", p("sign.key"), "-out", p("sign.pem"),
           "-subj", "/CN=Android Signing", "-days", "3650")
        sh("openssl", "crl2pkcs7", "-nocrl", "-certfile", p("sign.pem"),
           "-outform", "DER", "-out", p("CERT.RSA"))

        # Compact EC private key (PKCS#8). Encodes as 0x30 0x81 ..., which the old scanner missed.
        sh("openssl", "genpkey", "-algorithm", "EC",
           "-pkeyopt", "ec_paramgen_curve:P-256", "-out", p("ec.key"))

        # DER conversions.
        sh("openssl", "x509", "-in", p("ca.pem"), "-outform", "DER", "-out", p("ca.der"))
        sh("openssl", "x509", "-in", p("client.pem"), "-outform", "DER", "-out", p("client.der"))
        sh("openssl", "x509", "-in", p("decoy.pem"), "-outform", "DER", "-out", p("decoy.der"))
        sh("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", p("client.key"),
           "-outform", "DER", "-out", p("client_key.der"))
        sh("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", p("decoy.key"),
           "-outform", "DER", "-out", p("decoy_key.der"))
        sh("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", p("ec.key"),
           "-outform", "DER", "-out", p("ec_key.der"))

        ca_der = der(p("ca.der"), "cert")
        client_der = der(p("client.der"), "cert")
        decoy_der = der(p("decoy.der"), "cert")
        client_key_der = der(p("client_key.der"), "rsa_pkcs8")
        decoy_key_der = der(p("decoy_key.der"), "rsa_pkcs8")
        ec_key_der = der(p("ec_key.der"), "ec_pkcs8")
        cert_rsa = der(p("CERT.RSA"), "pkcs7")
        tiny_ec_cert = build_tiny_ec_cert()

        assert client_key_der[1] == 0x82  # sanity: 2048-bit RSA key uses 0x82
        assert ec_key_der[1] == 0x81      # sanity: compact EC key uses 0x81

        # A malformed near-miss: a real DER SEQUENCE header (0x30 0x82) whose declared length
        # runs off the end of the buffer. It is not valid DER and must be rejected.
        nearmiss = JPEG_MAGIC + bytes([0x30, 0x82, 0xFF, 0xFF]) + os.urandom(40)

        oversized = os.urandom(300 * 1024)  # above maxEntryBytes -> skipped, reported

        entries = [
            # (name, data, stored?)
            ("META-INF/CERT.RSA", cert_rsa, True),
            ("AndroidManifest.xml", os.urandom(504), True),
            ("res/drawable/glass_ca.jpg", jpeg_wrap(ca_der), False),        # deflated
            ("res/drawable/glass_client.jpg", jpeg_wrap(client_der), True),  # stored
            ("res/drawable/glass_key.jpg", jpeg_wrap(client_key_der), False),
            ("res/raw/decoy_cert.jpg", jpeg_wrap(decoy_der), True),
            ("res/raw/decoy_key.jpg", jpeg_wrap(decoy_key_der), False),
            ("res/raw/tiny_ec_cert.jpg", jpeg_wrap(tiny_ec_cert), True),
            ("res/raw/ec_key.jpg", jpeg_wrap(ec_key_der), False),
            ("res/drawable/real_icon.png", b"\x89PNG\r\n\x1a\n" + os.urandom(4000), True),
            ("assets/blob.bin", os.urandom(9000), True),
            ("res/raw/nearmiss.jpg", nearmiss, True),
            ("lib/arm64-v8a/libbig.so", oversized, True),
        ]

        if os.path.exists(OUT):
            os.remove(OUT)
        with zipfile.ZipFile(OUT, "w") as z:
            for name, data, stored in entries:
                zi = zipfile.ZipInfo(name)
                zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                z.writestr(zi, data)

        print(f"Wrote {OUT} ({os.path.getsize(OUT)} bytes)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
