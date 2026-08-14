import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('telemetry watchdog (req 7)', () {
    test('a telemetry stall while moving escalates and attempts a stop', () {
      runControl((h) {
        cruise(h, 8);
        expect(h.coord.phase, MotionPhase.moving);
        final stopsBefore = h.link.stopSends;

        h.link.telemetryStalled = true;
        h.tick(const Duration(milliseconds: 1200)); // past the 1s watchdog

        expect(h.coord.phase, MotionPhase.escalated);
        expect(h.coord.state.escalation, EscalationReason.telemetryStall);
        expect(h.hasEvent<Escalated>(), isTrue);
        expect(h.link.stopSends, greaterThan(stopsBefore),
            reason: 'the watchdog attempts a stop');
      });
    });

    test('the watchdog does not fire while at rest', () {
      runControl((h) {
        // Never commanded to move; a stall must not escalate.
        h.link.telemetryStalled = true;
        h.tick(const Duration(milliseconds: 1500));
        expect(h.coord.phase, MotionPhase.idle);
        expect(h.hasEvent<Escalated>(), isFalse);
      });
    });
  });
}
