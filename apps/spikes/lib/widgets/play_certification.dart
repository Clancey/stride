import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../bridge.dart';
import '../theme/stride_tokens.dart';
import 'stride_sheet.dart';

/// Breaks a long decimal id into groups a person can actually copy down.
///
/// The GSF id is up to twenty digits and has to be transcribed by hand onto a
/// phone, standing next to a treadmill, from a screen a metre away. An unbroken
/// run of digits is the worst possible way to present that: readers lose their
/// place mid-number and there is no checksum to catch it. Groups of four are the
/// same shape as a card number, which is the one long number everyone has
/// already practised copying.
///
/// Grouped from the left so the leading digits stay aligned with what the reader
/// has typed so far; the short group, if any, falls at the end.
String groupDigits(String digits, {int size = 4}) {
  if (size < 1) return digits;
  final buffer = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && i % size == 0) buffer.write('\u2009'); // thin space
    buffer.write(digits[i]);
  }
  return buffer.toString();
}

/// What the console knows about its own Play certification.
class PlayCertificationInfo {
  const PlayCertificationInfo({
    required this.hasGms,
    required this.deviceId,
    required this.registrationUrl,
  });

  factory PlayCertificationInfo.fromMap(Map<String, dynamic> map) {
    final id = map['gsfAndroidId'] as String?;
    return PlayCertificationInfo(
      hasGms: map['hasGms'] == true,
      deviceId: (id == null || id.isEmpty) ? null : id,
      registrationUrl:
          map['registrationUrl'] as String? ??
          'https://www.google.com/android/uncertified',
    );
  }

  static const absent = PlayCertificationInfo(
    hasGms: false,
    deviceId: null,
    registrationUrl: 'https://www.google.com/android/uncertified',
  );

  final bool hasGms;

  /// Decimal, never hex. Null until GSF has checked in with Google, which needs
  /// a network and, usually, a reboot.
  final String? deviceId;
  final String registrationUrl;

  /// Stripped of the scheme, because nobody types `https://` into a phone.
  String get shortUrl => registrationUrl
      .replaceFirst(RegExp(r'^https?://'), '')
      .replaceFirst(RegExp(r'^www\.'), '');
}

Future<PlayCertificationInfo> readPlayCertification() async {
  try {
    return PlayCertificationInfo.fromMap(await SpikeBridge.playCertification());
  } catch (_) {
    return PlayCertificationInfo.absent;
  }
}

/// A quiet, permanent pointer to the fix for "this device isn't certified".
///
/// Deliberately not a warning and not dismissible. Stride cannot tell whether the
/// console has already been registered — Google exposes no such signal — so an
/// alarm would cry wolf at everyone who already did it, and a dismiss button would
/// hide the answer from the one tester who meets the error next month. A single
/// calm row that is always in the same place is the honest shape for something
/// that is true, optional, and needed exactly once.
class PlayCertificationRow extends StatelessWidget {
  const PlayCertificationRow({super.key, required this.info});

  final PlayCertificationInfo info;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: StrideColors.panel,
      borderRadius: BorderRadius.circular(StrideRadius.md),
      child: InkWell(
        borderRadius: BorderRadius.circular(StrideRadius.md),
        onTap: () => showPlayCertificationSheet(context, info),
        child: Padding(
          padding: const EdgeInsets.all(StrideSpace.md),
          child: Row(
            children: [
              const Icon(
                Icons.verified_user_outlined,
                color: StrideColors.info,
              ),
              const SizedBox(width: StrideSpace.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Play says this console is not certified?',
                      style: theme.textTheme.titleMedium,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Expected. Register the console once and it goes away.',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: StrideColors.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: StrideSpace.md),
              const Icon(Icons.chevron_right, color: StrideColors.textMuted),
            ],
          ),
        ),
      ),
    );
  }
}

/// The HUD draws its own bars in separate system windows, so this goes through
/// [showStrideSheet] rather than a stock modal sheet: the last step is the one
/// that must not be clipped.
Future<void> showPlayCertificationSheet(
  BuildContext context,
  PlayCertificationInfo info,
) {
  return showStrideSheet<void>(
    context: context,
    builder: (context) => PlayCertificationBody(info: info),
  );
}

/// The whole procedure on one screen, in the order it happens.
class PlayCertificationBody extends StatelessWidget {
  const PlayCertificationBody({super.key, required this.info});

  final PlayCertificationInfo info;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final id = info.deviceId;
    // The console is 1920 wide. Left unconstrained, every line of this ran the
    // full width - about two hundred characters - and the Copy button ended up a
    // metre of screen away from the number it copies. Hold the whole procedure
    // to one readable measure and centre it.
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        StrideSpace.lg,
        0,
        StrideSpace.lg,
        StrideSpace.lg,
      ),
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 860),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Certify this console with Google',
                style: theme.textTheme.headlineSmall,
              ),
              const SizedBox(height: StrideSpace.xs),
              Text(
                'Google certifies a console when its manufacturer submits it. Nobody '
                'submitted this one, so Play refuses to sign in until you vouch for '
                'it yourself. This is a one-time step, per Google account.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: StrideColors.textMuted,
                ),
              ),
              const SizedBox(height: StrideSpace.lg),
              if (id == null)
                const _NoIdYet()
              else ...[
                _DeviceId(id: id),
                const SizedBox(height: StrideSpace.lg),
                _Step(
                  n: 1,
                  title: 'On your phone, open ${info.shortUrl}',
                  detail:
                      'Sign in as the account this console will use. A different '
                      'account will not work — the registration is per account.',
                ),
                _Step(
                  n: 2,
                  title: 'Type the number above and register',
                  detail:
                      'Enter it exactly as shown, without the spaces. The page '
                      'wants this decimal number, not the hex one other apps print.',
                ),
                _Step(
                  n: 3,
                  title:
                      'Back here: clear data on Play Services and Play Store',
                  detail:
                      'Settings, Apps, then Storage, Clear data. Both apps.',
                ),
                _Step(
                  n: 4,
                  title: 'Restart the console, then wait a few minutes',
                  detail:
                      'Google can take a little while to honour the registration. '
                      'If Play still refuses, leave it and try again later.',
                  last: true,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _DeviceId extends StatelessWidget {
  const _DeviceId({required this.id});

  final String id;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.md,
        vertical: StrideSpace.md,
      ),
      decoration: BoxDecoration(
        color: StrideColors.ink,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'THIS CONSOLE\u2019S DEVICE ID',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: StrideColors.textMuted,
              letterSpacing: 1.4,
            ),
          ),
          const SizedBox(height: StrideSpace.xs),
          // Never wrapped. A number that breaks across lines invites the reader
          // to lose the tail, and the tail is the half with no landmarks - so it
          // shrinks to fit instead, staying as large as one line allows.
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: SelectableText(
              groupDigits(id),
              maxLines: 1,
              style: const TextStyle(
                fontFamily: 'monospace',
                fontFamilyFallback: ['RobotoMono', 'Courier'],
                fontSize: 46,
                height: 1.15,
                fontWeight: FontWeight.w600,
                color: StrideColors.accent,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ),
          const SizedBox(height: StrideSpace.xs),
          // Under the number, not floated to the far edge: on a 1920-wide console
          // a right-aligned button ends up half a screen from the thing it acts on.
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: id));
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Device id copied')),
                );
              },
              icon: const Icon(Icons.copy_all_outlined, size: 18),
              label: const Text('Copy'),
            ),
          ),
        ],
      ),
    );
  }
}

class _NoIdYet extends StatelessWidget {
  const _NoIdYet();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.ink,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.warning),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'No device id yet',
            style: theme.textTheme.titleMedium?.copyWith(
              color: StrideColors.warning,
            ),
          ),
          const SizedBox(height: StrideSpace.xxs),
          Text(
            'Play Services has not checked in with Google yet, so it has not been '
            'given an id to register. Put the console on a network, restart it, '
            'and come back — the number appears here once it has one.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: StrideColors.textMuted,
            ),
          ),
        ],
      ),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({
    required this.n,
    required this.title,
    required this.detail,
    this.last = false,
  });

  final int n;
  final String title;
  final String detail;
  final bool last;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: last ? 0 : StrideSpace.md),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            alignment: Alignment.center,
            decoration: const BoxDecoration(
              color: StrideColors.panelHigh,
              shape: BoxShape.circle,
            ),
            child: Text(
              '$n',
              style: theme.textTheme.labelLarge?.copyWith(
                color: StrideColors.accent,
              ),
            ),
          ),
          const SizedBox(width: StrideSpace.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: theme.textTheme.titleSmall),
                const SizedBox(height: 2),
                Text(
                  detail,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: StrideColors.textMuted,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
