import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('command acknowledgement + timeout (req 6)', () {
    test('a dropped ack with no telemetry times out', () {
      runControl((h) {
        h.link.ackMode = AckMode.drop;
        h.link.telemetryStalled = true; // no confirming telemetry either
        final c = capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 600));
        expect(c.result!.status, CommandStatus.timedOut);
      });
    });

    test('a confirming telemetry change stands in for a missing ack', () {
      runControl((h) {
        h.link.ackMode = AckMode.drop; // ack never arrives
        // Telemetry keeps flowing and the belt physically reaches the setpoint.
        final c = capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 600));
        expect(c.result!.ok, isTrue,
            reason: 'observed motion toward the setpoint confirms the command');
      });
    });

    test('a normal ack resolves promptly', () {
      runControl((h) {
        final c = capture(h.setSpeed(6));
        h.tick(const Duration(milliseconds: 100));
        expect(c.result!.ok, isTrue);
      });
    });
  });
}
