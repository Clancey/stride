import 'package:flutter/material.dart';

import '../theme/stride_tokens.dart';
import 'apps_screen.dart';
import 'environment_screen.dart';
import 'glassos_screen.dart';
import 'launcher_screen.dart';
import 'media_screen.dart';
import 'navigation_screen.dart';
import 'overlay_screen.dart';

class SpikeDiagnostic {
  const SpikeDiagnostic(
    this.id,
    this.title,
    this.question,
    this.icon,
    this.builder,
  );

  final String id;
  final String title;
  final String question;
  final IconData icon;
  final WidgetBuilder builder;
}

const List<SpikeDiagnostic> spikeDiagnostics = <SpikeDiagnostic>[
  SpikeDiagnostic(
    'ENV',
    'Environment',
    'What is this console, really?',
    Icons.memory,
    _envBuilder,
  ),
  SpikeDiagnostic(
    'S1',
    'Launcher replace + revert',
    'Can Stride become HOME, and can we get back to iFit?',
    Icons.home_outlined,
    _launcherBuilder,
  ),
  SpikeDiagnostic(
    'S2',
    'GlassOS certs + gRPC',
    'Can we extract the credentials on-device and complete an mTLS gRPC call?',
    Icons.vpn_key_outlined,
    _glassosBuilder,
  ),
  SpikeDiagnostic(
    'S3',
    'Overlay + edge gestures',
    'Does the overlay survive real apps, and how much touch does it steal?',
    Icons.layers_outlined,
    _overlayBuilder,
  ),
  SpikeDiagnostic(
    'S4',
    'Media apps present',
    'What actually installs and plays on a non-GMS console?',
    Icons.apps_outlined,
    _appsBuilder,
  ),
  SpikeDiagnostic(
    'S5',
    'MediaSession control',
    'Can we observe and pause/resume the apps that survived S4?',
    Icons.music_note_outlined,
    _mediaBuilder,
  ),
  SpikeDiagnostic(
    'S10',
    'Back / Home / Recents',
    'Does the accessibility service work, and does it survive reboot?',
    Icons.swipe_left_outlined,
    _navBuilder,
  ),
];

Widget _envBuilder(BuildContext _) => const EnvironmentScreen();
Widget _launcherBuilder(BuildContext _) => const LauncherScreen();
Widget _glassosBuilder(BuildContext _) => const GlassOsScreen();
Widget _overlayBuilder(BuildContext _) => const OverlayScreen();
Widget _appsBuilder(BuildContext _) => const AppsScreen();
Widget _mediaBuilder(BuildContext _) => const MediaScreen();
Widget _navBuilder(BuildContext _) => const NavigationScreen();

class DiagnosticsHome extends StatelessWidget {
  const DiagnosticsHome({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Stride diagnostics'),
        bottom: const PreferredSize(
          preferredSize: Size.fromHeight(36),
          child: Padding(
            padding: EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Phase 0 hardware probes. Nothing here commands the motor.',
                style: TextStyle(fontSize: 14, color: StrideColors.textMuted),
              ),
            ),
          ),
        ),
      ),
      body: ListView.separated(
        padding: const EdgeInsets.all(StrideSpace.md),
        itemCount: spikeDiagnostics.length,
        separatorBuilder: (context, index) =>
            const SizedBox(height: StrideSpace.sm),
        itemBuilder: (context, i) {
          final spike = spikeDiagnostics[i];
          return Card(
            clipBehavior: Clip.antiAlias,
            child: ListTile(
              minVerticalPadding: StrideSpace.md,
              minLeadingWidth: StrideSpace.xl,
              leading: CircleAvatar(child: Icon(spike.icon)),
              title: Text('${spike.id} — ${spike.title}'),
              subtitle: Text(spike.question),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(
                context,
              ).push(MaterialPageRoute<void>(builder: spike.builder)),
            ),
          );
        },
      ),
    );
  }
}
