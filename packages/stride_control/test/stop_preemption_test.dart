import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('stop preemption (req 4)', () {
    test('a stop cancels every queued command and transmits immediately', () {
      runControl((h) {
        // Keep the worker busy so the followers stay queued.
        final s1 = capture(h.setSpeed(4));
        final s2 = capture(h.setSpeed(6));
        final s3 = capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 40));

        final stop = capture(h.coord.stop());
        // Let the drained completers deliver before asserting on them.
        h.tick(Duration.zero);
        // The queued followers are cancelled at once.
        expect(s2.result!.status, CommandStatus.superseded);
        expect(s3.result!.status, CommandStatus.superseded);

        h.tick(const Duration(milliseconds: 600));
        expect(h.link.stopSends, greaterThanOrEqualTo(1));
        expect(stop.result!.ok, isTrue);
        expect(h.coord.commandedSpeed.kph, 0);
        expect(s1.result, isNotNull);
      }, ackDelay: const Duration(milliseconds: 300));
    });

    test('stop is not delayed by a hung in-flight write', () {
      runControl((h) {
        // Drop the ack so the in-flight write never completes on its own.
        h.link.ackMode = AckMode.drop;
        capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 50));

        h.link.ackMode = AckMode.normal; // stop can still ack
        final stop = capture(h.coord.stop());
        // Only the preempt grace (100ms) plus the stop ack (50ms) is needed;
        // the hung write's full command timeout is not waited on.
        h.tick(const Duration(milliseconds: 300));
        expect(h.link.stopSends, greaterThanOrEqualTo(1));
        expect(stop.result!.ok, isTrue);
      });
    });
  });
}
