// Tests for the DER/ASN.1 credential scanner.
//
// `test/fixtures/fake_console.apk` is an adversarial stand-in for the iFit console package. It is
// built by `test/fixtures/generate_fake_console.py` and contains, among decoys:
//   * META-INF/CERT.RSA - a PKCS#7 holding a self-signed APK *signing* cert that must be skipped.
//   * The real GlassOS CA (self-signed) + client cert (CN=com.ifit.dev_app, issued by the CA)
//     + the client's RSA private key. The single confidently selectable set.
//   * A decoy self-signed cert + matching RSA key. A complete RSA pair on its own, so modulus
//     binding alone finds it, but it chains to no present CA and must be rejected.
//   * A compact EC private key and a hand-built EC certificate, both encoded with a 0x81 length.
//   * Fake-JPEG wrappers (stored and deflated), a real PNG, random bytes, a malformed near-miss,
//     and an oversized entry.
//
// All certificates and keys are generated throwaways - no real secrets.

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

    // ---------------------------------------------------------------- Bug 1: correct selection

    test('selects a confident, cryptographically bound credential set', () {
      expect(result.isComplete, isTrue, reason: result.selection.reason);
      expect(result.selection.confidence, SelectionConfidence.confident);
      expect(result.selection.bindingVerified, isTrue);
    });

    test('does NOT pick the APK signing certificate from META-INF as the CA', () {
      // Bug 1 proof: the signing cert (CN=Android Signing) lives in META-INF/CERT.RSA. The old
      // "first self-signed cert is the CA" logic could select it. It must never appear at all.
      final subjects = result.certificates.map((c) => c.subject).toSet();
      expect(subjects, isNot(contains('Android Signing')),
          reason: 'META-INF is the APK signing block, not application data, and must be skipped');
      expect(result.caCertificate!.subject, 'GlassOS Test CA');
    });

    test('binds the client key to the client certificate by RSA modulus', () {
      // Bug 1 proof: selection is by modulus equality, not by position. The decoy self-signed pair
      // is also a valid RSA pair, so a naive selector could pick it; the real client wins because
      // it also chains to the CA.
      final client = result.clientCertificate!;
      final key = result.clientKey!;
      expect(client.subject, 'com.ifit.dev_app');
      expect(client.rsaModulus, isNotNull);
      expect(key.rsaModulus, isNotNull);
      expect(key.rsaModulus, equals(client.rsaModulus),
          reason: 'the selected key must be the client certificate\'s own key');
    });

    test('chains the CA to the client certificate by issuer/subject DER', () {
      // Bug 1 proof: the CA is chosen because its subject DER equals the client cert issuer DER,
      // not because it happens to be self-signed (the decoy cert and signing cert are self-signed
      // too).
      final ca = result.caCertificate!;
      final client = result.clientCertificate!;
      expect(ca.subjectDer, isNotNull);
      expect(client.issuerDer, equals(ca.subjectDer));
      expect(ca.selfSigned, isTrue);
      expect(client.selfSigned, isFalse);
      expect(ca.bytes, isNot(equals(client.bytes)));
    });

    test('does not select the decoy self-signed pair', () {
      // The decoy (CN=Decoy Widget) is bound key+cert but chains to no present CA.
      expect(result.clientCertificate!.subject, isNot('Decoy Widget'));
      expect(result.caCertificate!.subject, isNot('Decoy Widget'));
    });

    test('classifies and reports EC material rather than mis-binding it', () {
      // Bug 1 proof: EC keys are identified and excluded from RSA modulus binding.
      final ecKeys = result.privateKeys
          .where((k) => k.keyAlgorithm == KeyAlgorithm.ec)
          .toList();
      expect(ecKeys, isNotEmpty, reason: 'the fixture contains a compact EC private key');
      for (final k in ecKeys) {
        expect(k.rsaModulus, isNull);
        expect(k.describe(), contains('EC'));
      }
    });

    test('reports an honest reason when a set cannot be confidently chosen', () {
      // An archive with only an EC key (no RSA pair) must be incomplete with a stated reason,
      // never a confident wrong guess.
      final ec = result.privateKeys
          .firstWhere((k) => k.keyAlgorithm == KeyAlgorithm.ec);
      final onlyEc = ExtractionResult(findings: [result.caCertificate!, ec], log: const []);
      expect(onlyEc.isComplete, isFalse);
      expect(onlyEc.selection.confidence, isNot(SelectionConfidence.confident));
      expect(onlyEc.selection.reason, contains('EC'));
    });

    test('flags ambiguity instead of guessing when two chained pairs exist', () {
      // Two distinct client certs, each bound to a key and each chaining to a present CA, is
      // genuinely ambiguous. The result must say so rather than pick one.
      final ambiguous = ExtractionResult(findings: _twoChains(result), log: const []);
      expect(ambiguous.isComplete, isFalse);
      expect(ambiguous.selection.confidence, SelectionConfidence.ambiguous);
    });

    // ---------------------------------------------------------------- Bug 2: DER length parsing

    test('finds DER objects that use a 0x81 length form', () {
      // Bug 2 proof: the compact EC key and the tiny EC cert both encode their length as 0x81. The
      // old scanner only matched 0x30 0x82 and would miss them entirely.
      final short = result.findings.where((f) => f.bytes.length < 256).toList();
      expect(short, isNotEmpty,
          reason: 'a scanner limited to 0x30 0x82 would find nothing under 256 bytes');
      // Every short finding must actually be encoded with a long-form single length byte (0x81).
      for (final f in short) {
        expect(f.bytes[1], 0x81,
            reason: '${f.zipEntry} should exercise the 0x81 length branch');
      }
    });

    test('finds the compact EC private key and the tiny EC certificate', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, contains('res/raw/ec_key.jpg'));
      expect(entries, contains('res/raw/tiny_ec_cert.jpg'));
    });

    test('rejects a malformed near-miss whose length overruns the buffer', () {
      // Bug 2 proof: 0x30 0x82 with an overrunning declared length is not valid DER. Exact
      // structural consumption in _childrenInRange also rejects valid-prefix-then-garbage.
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, isNot(contains('res/raw/nearmiss.jpg')));
    });

    test('skips oversized entries and reports how many were skipped', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, isNot(contains('lib/arm64-v8a/libbig.so')),
          reason: 'entries above maxEntryBytes are skipped to stay fast on a slow SoC');
      expect(result.log.any((l) => l.contains('oversized')), isTrue,
          reason: 'a skipped entry must be logged so a "found nothing" run is diagnosable');
      expect(result.log.any((l) => l.contains('META-INF')), isTrue);
    });

    // ---------------------------------------------------------------- decoys and hygiene

    test('recovers credentials hidden behind fake JPEG magic', () {
      for (final f in result.findings) {
        expect(f.offset, greaterThan(0),
            reason: '${f.zipEntry} should have a wrapper before the DER');
        expect(f.zipEntry, endsWith('.jpg'));
      }
    });

    test('handles both stored and deflated zip entries', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, contains('res/drawable/glass_ca.jpg')); // deflated
      expect(entries, contains('res/drawable/glass_client.jpg')); // stored
    });

    test('classifies the private key as PKCS#8', () {
      expect(result.clientKey!.kind, DerKind.privateKeyPkcs8);
    });

    test('ignores non-credential decoys', () {
      final entries = result.findings.map((f) => f.zipEntry).toSet();
      expect(entries, isNot(contains('assets/blob.bin')));
      expect(entries, isNot(contains('res/drawable/real_icon.png')));
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

// Assembles a findings list with two distinct client certificates that are each bound to a key and
// each name the real CA as issuer, to exercise the ambiguity path. The fixture ships only one such
// client, so the second is synthesized from the real one with a distinct subject DER (but the same
// issuer DER and the same key modulus), which is enough to create two competing chains.
List<DerFinding> _twoChains(ExtractionResult r) {
  final ca = r.caCertificate!;
  final client = r.clientCertificate!;
  final key = r.clientKey!;
  final secondClient = DerFinding(
    kind: DerKind.certificate,
    bytes: client.bytes,
    zipEntry: 'synthetic/second_client.jpg',
    offset: 1,
    subject: 'other.client',
    issuer: client.issuer,
    subjectDer: '${client.subjectDer}00', // distinct subject DER
    issuerDer: client.issuerDer, // same issuer -> chains to the same CA
    selfSigned: false,
    keyAlgorithm: KeyAlgorithm.rsa,
    rsaModulus: key.rsaModulus, // bound to the same key so it forms a valid pair
  );
  return [ca, client, key, secondClient];
}
