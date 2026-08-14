import 'package:flutter/material.dart';

import '../model/workout_goal.dart';
import '../theme/stride_tokens.dart';

/// Full-screen goal picker shown before Stride's timer starts.
///
/// Stride cannot drive the treadmill, so nothing here touches the belt: the
/// picker only records what the rider wants Stride to *track* and then hands off
/// to the same timer-start the launcher already uses via [onConfirm].
class StartWorkoutScreen extends StatefulWidget {
  const StartWorkoutScreen({
    super.key,
    required this.onConfirm,
    this.initialGoal = const WorkoutGoal.none(),
    this.workoutUnderway = false,
  });

  /// Persists the goal and starts Stride's timer, returning whether the start
  /// succeeded. Injected so the launcher owns the one real start path and tests
  /// can drive the screen without a platform channel.
  final Future<bool> Function(WorkoutGoal goal) onConfirm;

  final WorkoutGoal initialGoal;

  /// True when a session is already running, so this screen is changing the
  /// goal of a workout in progress rather than starting a new one. Only the
  /// wording changes: promising to "start" something already under way reads
  /// as though the elapsed time is about to be thrown away.
  final bool workoutUnderway;

  static const List<double> distancePresets = <double>[1, 2, 3.1, 5, 6.2, 10];
  static const List<int> timePresetMinutes = <int>[10, 20, 30, 45, 60];

  @override
  State<StartWorkoutScreen> createState() => _StartWorkoutScreenState();
}

class _StartWorkoutScreenState extends State<StartWorkoutScreen> {
  late WorkoutGoalKind _kind;

  // Distance is tracked in whole tenths of a mile to keep the stepper free of
  // floating-point drift as the rider taps it up and down.
  int _distanceTenths = 31; // 5K by default: a confirmable, common target.
  int _timeMinutes = 30;

  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    final goal = widget.initialGoal;
    _kind = goal.isNone ? WorkoutGoalKind.distance : goal.kind;
    if (goal.kind == WorkoutGoalKind.distance && goal.target > 0) {
      _distanceTenths = (goal.target * 10).round().clamp(1, 300);
    } else if (goal.kind == WorkoutGoalKind.time && goal.target > 0) {
      _timeMinutes = (goal.target / 60).round().clamp(1, 180);
    }
  }

  double get _distanceMiles => _distanceTenths / 10;

  WorkoutGoal get _goal => switch (_kind) {
    WorkoutGoalKind.none => const WorkoutGoal.none(),
    WorkoutGoalKind.distance => WorkoutGoal.distance(_distanceMiles),
    WorkoutGoalKind.time => WorkoutGoal.time(Duration(minutes: _timeMinutes)),
  };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.workoutUnderway ? 'Workout goal' : 'Start workout'),
        leading: IconButton(
          tooltip: 'Back',
          icon: const Icon(Icons.arrow_back_rounded, size: 30),
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            StrideSpace.xl,
            StrideSpace.md,
            StrideSpace.xl,
            StrideSpace.xl,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Set a goal for Stride to track. Stride times your workout and '
                "follows along — it can't move the belt.",
                style: Theme.of(context).textTheme.bodyLarge,
              ),
              const SizedBox(height: StrideSpace.lg),
              _ModeSelector(
                selected: _kind,
                onChanged: (kind) => setState(() => _kind = kind),
              ),
              const SizedBox(height: StrideSpace.lg),
              Expanded(
                child: SingleChildScrollView(child: _buildPicker(context)),
              ),
              const SizedBox(height: StrideSpace.md),
              _ConfirmBar(
                underway: widget.workoutUnderway,
                goal: _goal,
                submitting: _submitting,
                onConfirm: _confirm,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPicker(BuildContext context) {
    return switch (_kind) {
      WorkoutGoalKind.none => const _NoGoalNotice(),
      WorkoutGoalKind.distance => _DistancePicker(
        tenths: _distanceTenths,
        onPreset: (miles) =>
            setState(() => _distanceTenths = (miles * 10).round()),
        onStep: (delta) => setState(
          () => _distanceTenths = (_distanceTenths + delta).clamp(1, 300),
        ),
      ),
      WorkoutGoalKind.time => _TimePicker(
        minutes: _timeMinutes,
        onPreset: (minutes) => setState(() => _timeMinutes = minutes),
        onStep: (delta) =>
            setState(() => _timeMinutes = (_timeMinutes + delta).clamp(1, 180)),
      ),
    };
  }

  Future<void> _confirm() async {
    if (_submitting) return;
    setState(() => _submitting = true);
    final ok = await widget.onConfirm(_goal);
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pop(true);
      return;
    }
    setState(() => _submitting = false);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Could not start the timer.')));
  }
}

class _ModeSelector extends StatelessWidget {
  const _ModeSelector({required this.selected, required this.onChanged});

  final WorkoutGoalKind selected;
  final ValueChanged<WorkoutGoalKind> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _ModeButton(
          icon: Icons.timer_outlined,
          label: 'No goal',
          value: WorkoutGoalKind.none,
          selected: selected == WorkoutGoalKind.none,
          onChanged: onChanged,
        ),
        const SizedBox(width: StrideSpace.sm),
        _ModeButton(
          icon: Icons.straighten_rounded,
          label: 'Distance',
          value: WorkoutGoalKind.distance,
          selected: selected == WorkoutGoalKind.distance,
          onChanged: onChanged,
        ),
        const SizedBox(width: StrideSpace.sm),
        _ModeButton(
          icon: Icons.schedule_rounded,
          label: 'Time',
          value: WorkoutGoalKind.time,
          selected: selected == WorkoutGoalKind.time,
          onChanged: onChanged,
        ),
      ],
    );
  }
}

class _ModeButton extends StatelessWidget {
  const _ModeButton({
    required this.icon,
    required this.label,
    required this.value,
    required this.selected,
    required this.onChanged,
  });

  final IconData icon;
  final String label;
  final WorkoutGoalKind value;
  final bool selected;
  final ValueChanged<WorkoutGoalKind> onChanged;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Material(
        color: selected ? StrideColors.accent : StrideColors.panelRaised,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          onTap: () => onChanged(value),
          child: Container(
            height: StrideSpace.minTouch,
            padding: const EdgeInsets.symmetric(horizontal: StrideSpace.sm),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(StrideRadius.lg),
              border: Border.all(
                color: selected ? StrideColors.accent : StrideColors.line,
                width: 1.6,
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 28,
                  color: selected ? StrideColors.ink : StrideColors.textMuted,
                ),
                const SizedBox(width: StrideSpace.xs),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        color: selected ? StrideColors.ink : StrideColors.text,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _NoGoalNotice extends StatelessWidget {
  const _NoGoalNotice();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(StrideSpace.lg),
      decoration: BoxDecoration(
        color: StrideColors.panelRaised,
        borderRadius: BorderRadius.circular(StrideRadius.xl),
        border: Border.all(color: StrideColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('No goal', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: StrideSpace.sm),
          Text(
            'Stride just runs the timer. You can still watch your distance and '
            'pace from the console — set a goal any time before you begin.',
            style: Theme.of(context).textTheme.bodyLarge,
          ),
        ],
      ),
    );
  }
}

class _DistancePicker extends StatelessWidget {
  const _DistancePicker({
    required this.tenths,
    required this.onPreset,
    required this.onStep,
  });

  final int tenths;
  final ValueChanged<double> onPreset;
  final ValueChanged<int> onStep;

  @override
  Widget build(BuildContext context) {
    final miles = tenths / 10;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _PresetWrap(
          children: [
            for (final preset in StartWorkoutScreen.distancePresets)
              _PresetChip(
                label: _presetLabel(preset),
                selected: (preset * 10).round() == tenths,
                onTap: () => onPreset(preset),
              ),
          ],
        ),
        const SizedBox(height: StrideSpace.lg),
        _StepperControl(
          caption: 'Custom distance',
          value: '${WorkoutGoal.formatMiles(miles)} mi',
          onDecrement: tenths > 1 ? () => onStep(-1) : null,
          onIncrement: tenths < 300 ? () => onStep(1) : null,
        ),
      ],
    );
  }

  static String _presetLabel(double miles) {
    if (miles == 3.1) return '5K';
    if (miles == 6.2) return '10K';
    return '${WorkoutGoal.formatMiles(miles)} mi';
  }
}

class _TimePicker extends StatelessWidget {
  const _TimePicker({
    required this.minutes,
    required this.onPreset,
    required this.onStep,
  });

  final int minutes;
  final ValueChanged<int> onPreset;
  final ValueChanged<int> onStep;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _PresetWrap(
          children: [
            for (final preset in StartWorkoutScreen.timePresetMinutes)
              _PresetChip(
                label: '$preset min',
                selected: preset == minutes,
                onTap: () => onPreset(preset),
              ),
          ],
        ),
        const SizedBox(height: StrideSpace.lg),
        _StepperControl(
          caption: 'Custom time',
          value: WorkoutGoal.formatClock(Duration(minutes: minutes)),
          onDecrement: minutes > 1 ? () => onStep(-1) : null,
          onIncrement: minutes < 180 ? () => onStep(1) : null,
        ),
      ],
    );
  }
}

/// Lays presets out as a grid of equal chips rather than a stack of full-width bars.
///
/// The console is 1920px wide: one preset per row wastes most of the screen and turns a six-item
/// choice into a scroll. Equal widths also stop "5K" and "10.0 mi" from reading as different kinds
/// of control just because their labels differ in length.
class _PresetWrap extends StatelessWidget {
  const _PresetWrap({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const gap = StrideSpace.sm;
        final available = constraints.maxWidth;
        // Aim for chips around 240dp; clamped so a narrow window never produces a single column of
        // slivers or a row too cramped to hit with a running hand.
        final columns = ((available + gap) / (240 + gap)).floor().clamp(2, 4);
        final width = (available - gap * (columns - 1)) / columns;
        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: [
            for (final child in children) SizedBox(width: width, child: child),
          ],
        );
      },
    );
  }
}

class _PresetChip extends StatelessWidget {
  const _PresetChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? StrideColors.accent : StrideColors.panelRaised,
      borderRadius: BorderRadius.circular(StrideRadius.lg),
      child: InkWell(
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(
            minWidth: 132,
            minHeight: StrideSpace.minTouch,
          ),
          padding: const EdgeInsets.symmetric(horizontal: StrideSpace.lg),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(StrideRadius.lg),
            border: Border.all(
              color: selected ? StrideColors.accent : StrideColors.line,
              width: 1.6,
            ),
          ),
          alignment: Alignment.center,
          child: Text(
            label,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              color: selected ? StrideColors.ink : StrideColors.text,
              fontWeight: FontWeight.w900,
            ),
          ),
        ),
      ),
    );
  }
}

class _StepperControl extends StatelessWidget {
  const _StepperControl({
    required this.caption,
    required this.value,
    required this.onDecrement,
    required this.onIncrement,
  });

  final String caption;
  final String value;
  final VoidCallback? onDecrement;
  final VoidCallback? onIncrement;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.panelRaised,
        borderRadius: BorderRadius.circular(StrideRadius.xl),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        children: [
          _StepButton(icon: Icons.remove_rounded, onPressed: onDecrement),
          Expanded(
            child: Column(
              children: [
                Text(caption, style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: StrideSpace.xxs),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    value,
                    style: const TextStyle(
                      color: StrideColors.text,
                      fontSize: 48,
                      height: 1,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1,
                    ),
                  ),
                ),
              ],
            ),
          ),
          _StepButton(icon: Icons.add_rounded, onPressed: onIncrement),
        ],
      ),
    );
  }
}

class _StepButton extends StatelessWidget {
  const _StepButton({required this.icon, required this.onPressed});

  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    return SizedBox.square(
      dimension: StrideSpace.primaryTouch,
      child: FilledButton(
        style: FilledButton.styleFrom(
          padding: EdgeInsets.zero,
          backgroundColor: enabled
              ? StrideColors.panelHigh
              : StrideColors.panelHigh.withValues(alpha: 0.5),
          foregroundColor: enabled ? StrideColors.text : StrideColors.textMuted,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(StrideRadius.lg),
          ),
        ),
        onPressed: onPressed,
        child: Icon(icon, size: 40),
      ),
    );
  }
}

class _ConfirmBar extends StatelessWidget {
  const _ConfirmBar({
    required this.goal,
    required this.submitting,
    required this.onConfirm,
    required this.underway,
  });

  final WorkoutGoal goal;
  final bool submitting;
  final Future<void> Function() onConfirm;
  final bool underway;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.panelRaised,
        borderRadius: BorderRadius.circular(StrideRadius.xl),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Goal', style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: StrideSpace.xxs),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    goal.label,
                    style: Theme.of(context).textTheme.displayLarge,
                  ),
                ),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  underway
                      ? 'Updates the goal on the workout already running. Your '
                            'elapsed time is kept.'
                      : "Starts Stride's timer and goal tracking. Use the "
                            "console's own controls to move the belt.",
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          const SizedBox(width: StrideSpace.lg),
          SizedBox(
            width: 300,
            child: FilledButton.icon(
              style: FilledButton.styleFrom(
                minimumSize: const Size(0, StrideSpace.primaryTouch),
                backgroundColor: StrideColors.accent,
                foregroundColor: StrideColors.ink,
              ),
              onPressed: submitting ? null : () => onConfirm(),
              icon: submitting
                  ? const SizedBox.square(
                      dimension: 28,
                      child: CircularProgressIndicator(
                        strokeWidth: 3,
                        color: StrideColors.ink,
                      ),
                    )
                  : Icon(
                      underway ? Icons.flag_rounded : Icons.play_arrow_rounded,
                      size: 38,
                    ),
              label: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  submitting
                      ? (underway ? 'Saving…' : 'Starting…')
                      : (underway ? 'Save goal' : 'Start workout'),
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
