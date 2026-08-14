// Tests for the DER/ASN.1 credential scanner.
//
// `test/fixtures/fake_console.apk` mimics how the iFit console package reportedly stores its
// GlassOS credentials: DER blobs hidden behind fake JPEG magic bytes, mixed in with real images,
// random data, a malformed near-miss, and an oversized entry. It contains no real secrets - the
// certificates are generated throwaways.
//
// This matters because the scanner runs on a user's console against a package we cannot see from
// here. If it silently finds nothing, or worse, misclassifies a CA as a leaf, the S2 spike fails
// for the wrong reason.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/cert_extractor.dart';

const String fixture = 'test/fixtures/fake_console.apk';

void main() {
  group('CertExtractor', () {
    late ExtractionResult result;

    setUpAll(() async {
      result = await CertExtractor.extractFromApk(fixture);
    });

    test('finds a complete credential set', () {
      expect(result.isComplete, isTrue,
          reason: 'found ${result.certificates.length} certs, '
              '${result.privateKeys.length} keys');
    });

    test('finds exactly two certificates and one key', () {
      expect(result.certificates, hasLength(2));
      expect(result.privateKeys, hasLength(1));
    });

    test('recovers credentials hidden behind fake JPEG magic', () {
      // Every finding sits at a non-zero offset, because the JPEG header precedes the DER.
      for (final f in result.findings) {
        expect(f.offset, greaterThan(0),
            reason: '${f.zipEntry} should have a wrapper before the DER');
        expect(f.zipEntry, endsWith('.jpg'));
      }
    });

    test('handles both stored and deflated zip entries', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, contains('res/drawable/bg_gradient_a.jpg')); // deflated
      expect(entries, contains('res/drawable/bg_gradient_b.jpg')); // stored
    });

    test('distinguishes the self-signed CA from the leaf client certificate', () {
      final ca = result.caCertificate!;
      final client = result.clientCertificate!;

      expect(ca.selfSigned, isTrue);
      expect(client.selfSigned, isFalse);
      expect(ca.subject, 'GlassOS Test CA');
      expect(client.subject, 'com.ifit.dev_app');
      expect(client.issuer, 'GlassOS Test CA',
          reason: 'the client cert must be issued by the CA we also extracted');
      expect(ca.bytes, isNot(equals(client.bytes)));
    });

    test('classifies the private key as PKCS#8', () {
      expect(result.clientKey!.kind, DerKind.privateKeyPkcs8);
    });

    test('ignores decoys, malformed DER, and oversized entries', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, isNot(contains('assets/blob.bin')));
      expect(entries, isNot(contains('res/drawable/real_icon.png')));
      expect(entries, isNot(contains('res/raw/nearmiss.jpg')),
          reason: 'a 0x30 0x82 header whose length overruns the buffer is not DER');
      expect(entries, isNot(contains('lib/arm64-v8a/libbig.so')),
          reason: 'entries above maxEntryBytes are skipped, so scanning stays fast on a slow SoC');
    });

    test('emits PEM that round-trips back to the original DER', () {
      for (final f in result.findings) {
        final pem = f.toPem();
        expect(pem, startsWith('-----BEGIN ${f.pemLabel}-----\n'));
        expect(pem.trimRight(), endsWith('-----END ${f.pemLabel}-----'));

        final body = pem
            .split('\n')
            .where((l) => l.isNotEmpty && !l.startsWith('-----'))
            .join();
        expect(base64.decode(body), equals(f.bytes),
            reason: '${f.zipEntry} PEM must decode back to the exact bytes found');

        // OpenSSL and Dart's SecurityContext both expect wrapped lines.
        for (final line in pem.split('\n').where((l) => !l.startsWith('-----'))) {
          expect(line.length, lessThanOrEqualTo(64));
        }
      }
    });

    test('labels each kind with the correct PEM header', () {
      expect(result.caCertificate!.pemLabel, 'CERTIFICATE');
      expect(result.clientKey!.pemLabel, 'PRIVATE KEY');
    });

    test('reports a missing APK without throwing', () async {
      final missing = await CertExtractor.extractFromApk('test/fixtures/nope.apk');
      expect(missing.findings, isEmpty);
      expect(missing.isComplete, isFalse);
      expect(missing.log.first, contains('not found'));
    });

    test('describe() never leaks key material', () {
      final description = result.clientKey!.describe();
      expect(description, contains('PKCS#8'));
      expect(description, isNot(contains(base64.encode(result.clientKey!.bytes))));
    });
  });
}
