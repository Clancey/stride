import 'package:test/test.dart';

import 'support.dart';

const _slowLimits = ControlLimits(
  maxSpeedAccel: 2.0,
  maxInclineSlew: 2.0,
  commandTimeout: Duration(milliseconds: 500),
  stopConfirmTimeout: Duration(seconds: 1),
  telemetryWatchdog: Duration(seconds: 1),
  stopPreemptGrace: Duration(milliseconds: 100),
  stopConfirmSpeedKph: 0.5,
);

void main() {
  group('acceleration / slew limiting (req 3)', () {
    test('a large increase is rate-limited, not applied in one jump', () {
      runControl((h) {
        // Establish a baseline setpoint first.
        final base = capture(h.setSpeed(4));
        h.tick(const Duration(milliseconds: 100));
        expect(base.result!.applied, closeTo(4, 1e-9));

        // Now ask for a big jump a short time later. It must be limited to
        // somewhere between the current speed and the request.
        h.tick(const Duration(milliseconds: 200));
        final jump = capture(h.setSpeed(12));
        h.tick(const Duration(milliseconds: 200));
        expect(jump.result!.status, CommandStatus.adjusted);
        expect(jump.result!.applied, greaterThan(4.0));
        expect(jump.result!.applied, lessThan(6.0));
      }, limits: _slowLimits);
    });

    test('deceleration is never rate-limited', () {
      runControl((h) {
        final up = capture(h.setSpeed(6));
        h.tick(const Duration(milliseconds: 4000)); // let it ramp up fully
        expect(h.coord.commandedSpeed.kph, closeTo(6, 0.5));
        expect(up.result, isNotNull);

        final down = capture(h.setSpeed(1));
        h.tick(const Duration(milliseconds: 200));
        // Immediate: applied exactly 1, not a rate-limited fraction.
        expect(down.result!.applied, closeTo(1, 1e-9));
        expect(down.result!.status, CommandStatus.applied);
      }, limits: _slowLimits);
    });
  });
}
