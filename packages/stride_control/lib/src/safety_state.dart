/// The state and events a consumer renders. The whole point of the coordinator
/// is that safety-critical facts are explicit values, not log lines: a UI can
/// only show "USE THE SAFETY KEY" if that condition is a first-class state it can
/// observe (PLAN.md 5.4).
library;

import 'package:meta/meta.dart';

import 'types.dart';

/// The coordinator's high-level motion phase.
enum MotionPhase {
  /// No usable link (disconnected, connecting or faulted).
  offline,

  /// Connected, belt at rest, safe to command.
  idle,

  /// Belt is commanded to move, or observed moving (external actor).
  moving,

  /// A stop has been sent and we are waiting for positive confirmation.
  stopping,

  /// Escalated: a stop could not be confirmed, or telemetry stalled while
  /// moving. Means "USE THE SAFETY KEY" - it does NOT mean stopped.
  escalated,

  /// Safety-key latch engaged. Requires an explicit local reset.
  latched,
}

/// Why the coordinator escalated. Both non-none reasons map to the same user
/// message: the software stop is not a fail-safe, so use the physical key.
enum EscalationReason { none, stopNotConfirmed, telemetryStall }

/// An immutable snapshot of everything a consumer needs to render safely.
@immutable
class SafetyState {
  const SafetyState({
    required this.phase,
    required this.link,
    required this.commandedSpeed,
    required this.observedSpeed,
    required this.commandedIncline,
    required this.generation,
    required this.latched,
    required this.escalation,
    this.escalationMessage,
  });

  const SafetyState.initial()
      : phase = MotionPhase.offline,
        link = LinkState.disconnected,
        commandedSpeed = const Speed.zero(),
        observedSpeed = const Speed.zero(),
        commandedIncline = const Percent.zero(),
        generation = 0,
        latched = false,
        escalation = EscalationReason.none,
        escalationMessage = null;

  final MotionPhase phase;
  final LinkState link;

  /// The last speed the coordinator told the belt to run at.
  final Speed commandedSpeed;

  /// The most recent speed the machine reported.
  final Speed observedSpeed;

  final Percent commandedIncline;

  /// Generation of the currently valid command context. Anything tagged with an
  /// older generation is stale and must be discarded.
  final int generation;

  final bool latched;
  final EscalationReason escalation;
  final String? escalationMessage;

  /// True while the belt is moving or being told to move. UI that hides the stop
  /// control must not do so while this is true (PLAN.md 3.3 safety constraint).
  bool get beltActive =>
      phase == MotionPhase.moving || phase == MotionPhase.stopping;

  bool get isEscalated => phase == MotionPhase.escalated;

  SafetyState copyWith({
    MotionPhase? phase,
    LinkState? link,
    Speed? commandedSpeed,
    Speed? observedSpeed,
    Percent? commandedIncline,
    int? generation,
    bool? latched,
    EscalationReason? escalation,
    String? escalationMessage,
    bool clearEscalationMessage = false,
  }) {
    return SafetyState(
      phase: phase ?? this.phase,
      link: link ?? this.link,
      commandedSpeed: commandedSpeed ?? this.commandedSpeed,
      observedSpeed: observedSpeed ?? this.observedSpeed,
      commandedIncline: commandedIncline ?? this.commandedIncline,
      generation: generation ?? this.generation,
      latched: latched ?? this.latched,
      escalation: escalation ?? this.escalation,
      escalationMessage: clearEscalationMessage
          ? null
          : (escalationMessage ?? this.escalationMessage),
    );
  }

  @override
  String toString() => 'SafetyState(${phase.name} link=${link.name} '
      'cmd=$commandedSpeed obs=$observedSpeed incline=$commandedIncline '
      'gen=$generation latched=$latched escalation=${escalation.name})';
}

/// A discrete safety event. States describe "what is true now"; events mark the
/// transitions a consumer may want to alarm on.
sealed class SafetyEvent {
  const SafetyEvent();
}

/// The coordinator escalated to "USE THE SAFETY KEY". This is the loud event.
class Escalated extends SafetyEvent {
  const Escalated(this.reason, this.message);

  final EscalationReason reason;
  final String message;

  @override
  String toString() => 'Escalated(${reason.name}: $message)';
}

/// The safety-key latch engaged.
class Latched extends SafetyEvent {
  const Latched();

  @override
  String toString() => 'Latched';
}

/// The latch was cleared by a local reset.
class LatchReset extends SafetyEvent {
  const LatchReset();

  @override
  String toString() => 'LatchReset';
}

/// A stop was positively confirmed (ack plus observed deceleration).
class StopConfirmed extends SafetyEvent {
  const StopConfirmed();

  @override
  String toString() => 'StopConfirmed';
}

/// The coordinator attached to an already-moving belt and reconciled to it
/// instead of assuming zero.
class AttachedToMovingBelt extends SafetyEvent {
  const AttachedToMovingBelt(this.observedSpeed);

  final Speed observedSpeed;

  @override
  String toString() => 'AttachedToMovingBelt($observedSpeed)';
}
