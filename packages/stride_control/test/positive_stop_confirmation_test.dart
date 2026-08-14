import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('positive stop confirmation (req 10)', () {
    test('a stop with ack and observed deceleration confirms', () {
      runControl((h) {
        cruise(h, 8);
        final stop = capture(h.coord.stop());
        h.tick(const Duration(milliseconds: 800));

        expect(stop.result!.status, CommandStatus.applied);
        expect(h.coord.phase, MotionPhase.idle);
        expect(h.hasEvent<StopConfirmed>(), isTrue);
      });
    });

    test('ack without observed deceleration escalates to USE THE SAFETY KEY',
        () {
      runControl((h) {
        cruise(h, 8);
        // Ack will arrive, but telemetry never shows the belt slowing.
        h.link.telemetryStalled = true;
        final stop = capture(h.coord.stop());
        h.tick(const Duration(milliseconds: 1200)); // past stopConfirmTimeout

        expect(h.coord.phase, MotionPhase.escalated);
        expect(h.coord.state.escalation, EscalationReason.stopNotConfirmed);
        expect(h.hasEvent<Escalated>(), isTrue);
        expect(stop.result!.status, CommandStatus.timedOut);
      });
    });

    test('a stop over a dead link escalates rather than reporting stopped', () {
      runControl((h) {
        cruise(h, 8);
        h.link.ackMode = AckMode.fail; // stop send fails
        final stop = capture(h.coord.stop());
        h.tick(const Duration(milliseconds: 200));

        expect(h.coord.phase, MotionPhase.escalated);
        expect(h.hasEvent<Escalated>(), isTrue);
        expect(stop.result!.ok, isFalse);
      });
    });
  });
}
