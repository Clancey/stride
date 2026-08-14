import 'package:test/test.dart';

import 'support.dart';

const _readOnlySpeed = ControlRanges(
  speed: Range.readOnly(),
  incline: Range(min: -3, max: 15, step: 0.5, writable: true),
  resistance: Range.readOnly(),
  machineType: 'fake-readonly',
);

void main() {
  group('device-level absolute clamps (req 2)', () {
    test('clamps an over-range speed to the device maximum', () {
      runControl((h) {
        final c = capture(h.setSpeed(30));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.status, CommandStatus.adjusted);
        expect(c.result!.applied, closeTo(19.3, 1e-9));
      });
    });

    test('a profile may lower the ceiling', () {
      runControl((h) {
        final c = capture(h.setSpeed(30));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.applied, closeTo(10, 1e-9));
      }, profile: const ProfileLimits(maxSpeed: Speed.kph(10)));
    });

    test('a profile can never raise the ceiling above the device limit', () {
      runControl((h) {
        final c = capture(h.setSpeed(30));
        h.tick(const Duration(milliseconds: 200));
        // Profile asks for 25 but the device tops out at 19.3; device wins.
        expect(c.result!.applied, closeTo(19.3, 1e-9));
      }, profile: const ProfileLimits(maxSpeed: Speed.kph(25)));
    });

    test('a non-writable axis is rejected before transmit', () {
      runControl((h) {
        final c = capture(h.setSpeed(5));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.status, CommandStatus.rejected);
        expect(c.result!.didTransmit, isFalse);
        expect(h.link.speedSends, 0);
      }, ranges: _readOnlySpeed);
    });

    test('incline is clamped and profile-tightened independently', () {
      runControl((h) {
        final c = capture(h.setIncline(40));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.applied, closeTo(12, 1e-9));
      }, profile: const ProfileLimits(maxIncline: Percent(12)));
    });
  });
}
