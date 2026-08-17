import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/widgets/play_certification.dart';

void main() {
  group('groupDigits', () {
    test('breaks a real GSF id into transcribable groups', () {
      // Thin spaces, not ordinary ones: they must not survive a copy/paste into
      // Google's field, and they must not read as a decimal separator.
      expect(groupDigits('9843086184167632639'),
          '9843\u20090861\u20098416\u20097632\u2009639');
    });

    test('leaves a short id alone', () {
      expect(groupDigits('123'), '123');
      expect(groupDigits('1234'), '1234');
    });

    test('groups from the left so the leading digits stay put', () {
      expect(groupDigits('12345'), '1234\u20095');
    });

    test('an empty id does not produce a stray separator', () {
      expect(groupDigits(''), '');
    });

    test('a nonsense group size degrades to the raw digits', () {
      expect(groupDigits('12345', size: 0), '12345');
    });
  });

  group('PlayCertificationInfo', () {
    test('reads the bridge snapshot', () {
      final info = PlayCertificationInfo.fromMap({
        'hasGms': true,
        'gsfAndroidId': '9843086184167632639',
        'gsfAndroidIdHex': '8899aabbccddeeff',
        'registrationUrl': 'https://www.google.com/android/uncertified',
      });
      expect(info.hasGms, isTrue);
      expect(info.deviceId, '9843086184167632639');
      // Nobody types a scheme into a phone.
      expect(info.shortUrl, 'google.com/android/uncertified');
    });

    test('an absent id is null rather than an empty string', () {
      final info = PlayCertificationInfo.fromMap({
        'hasGms': true,
        'gsfAndroidId': null,
      });
      expect(info.deviceId, isNull);
      expect(info.hasGms, isTrue);
    });

    test('an empty id counts as absent', () {
      final info = PlayCertificationInfo.fromMap({
        'hasGms': true,
        'gsfAndroidId': '',
      });
      expect(info.deviceId, isNull);
    });

    test('falls back to the registration url when the bridge omits it', () {
      final info = PlayCertificationInfo.fromMap({'hasGms': false});
      expect(info.shortUrl, 'google.com/android/uncertified');
      expect(info.hasGms, isFalse);
    });
  });

  group('PlayCertificationBody', () {
    // Scrolled, because that is how showStrideSheet presents it: the console is
    // 1080 tall but the sheet is bounded by the HUD's top inset, so the body has
    // to survive being taller than its viewport.
    Widget wrap(PlayCertificationInfo info) => MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: PlayCertificationBody(info: info),
            ),
          ),
        );

    testWidgets('shows the id grouped, and the steps in order', (tester) async {
      await tester.pumpWidget(wrap(const PlayCertificationInfo(
        hasGms: true,
        deviceId: '9843086184167632639',
        registrationUrl: 'https://www.google.com/android/uncertified',
      )));

      expect(find.text('9843\u20090861\u20098416\u20097632\u2009639'),
          findsOneWidget);
      expect(
        find.text('On your phone, open google.com/android/uncertified'),
        findsOneWidget,
      );
      // The decimal-vs-hex trap is the single most common way this procedure
      // fails, so the warning has to be on screen, not just in the docs.
      expect(
        find.textContaining('decimal number, not the hex one'),
        findsOneWidget,
      );
      expect(find.text('1'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
    });

    testWidgets('offers no steps when there is no id to register',
        (tester) async {
      await tester.pumpWidget(wrap(const PlayCertificationInfo(
        hasGms: true,
        deviceId: null,
        registrationUrl: 'https://www.google.com/android/uncertified',
      )));

      expect(find.text('No device id yet'), findsOneWidget);
      // Walking someone through registering nothing wastes their time and
      // teaches them the procedure does not work.
      expect(find.textContaining('Type the number above'), findsNothing);
    });
  });

  testWidgets('the row is calm, and opens the sheet', (tester) async {
    const info = PlayCertificationInfo(
      hasGms: true,
      deviceId: '9843086184167632639',
      registrationUrl: 'https://www.google.com/android/uncertified',
    );
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: PlayCertificationRow(info: info)),
    ));

    expect(find.text('Play says this console is not certified?'), findsOneWidget);
    expect(find.textContaining('Expected.'), findsOneWidget);

    await tester.tap(find.byType(PlayCertificationRow));
    await tester.pumpAndSettle();
    expect(find.text('Certify this console with Google'), findsOneWidget);
  });
}
