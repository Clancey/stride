/// Fault injection configuration.
///
/// This is the most valuable part of the mock (docs/PLAN.md section 5's required
/// failure-mode tests). Each flag/knob here maps to a hazard-table row. The
/// server, machine, and REPL read this shared object; the REPL and CLI flags
/// mutate it.
///
/// Faults split into two kinds:
///   - CONTINUOUS conditions held in this object (delayed acks, dropped acks,
///     stalled telemetry, client-lost policy). The server consults these live.
///   - ONE-SHOT events (server die/restart, link drop, safety-key pull, hardware
///     button press) are methods on the machine/host, triggered by the REPL.
library;

/// What the belt does when the controlling client disappears.
///
/// This is an OPEN QUESTION on real hardware (docs/PLAN.md 5.5 and hazard row 1):
/// nobody has published what GlassOS does when its client dies. The whole point
/// of making it configurable is to let the coordinator's tests assert against
/// both answers, since the hazard analysis depends on which one is true.
enum ClientLostBehavior {
  /// Belt keeps running. This is the dangerous assumption; if real hardware does
  /// this, no software fail-safe is achievable and the docs must say so.
  keepMoving,

  /// Belt ramps to a stop. The optimistic assumption.
  stopBelt,
}

class FaultState {
  /// Extra latency added before a SetSpeed/SetIncline ack is returned.
  Duration ackDelay = Duration.zero;

  /// When true, command acks are dropped: the command still affects the belt
  /// (the machine "heard" it) but the client never gets confirmation. Models a
  /// lost reply on a flaky link (hazard: "Stop command lost on a failed link").
  bool dropAcks = false;

  /// When true, telemetry streams stop emitting even though the belt keeps
  /// moving. Models "telemetry stalls while belt commanded to move" (hazard row
  /// 3) which the coordinator must catch with a watchdog.
  bool stallTelemetry = false;

  /// Belt behavior on controlling-client loss. See [ClientLostBehavior].
  ClientLostBehavior clientLostBehavior = ClientLostBehavior.keepMoving;

  Map<String, Object> describe() => {
    'ackDelayMs': ackDelay.inMilliseconds,
    'dropAcks': dropAcks,
    'stallTelemetry': stallTelemetry,
    'clientLostBehavior': clientLostBehavior.name,
  };
}
