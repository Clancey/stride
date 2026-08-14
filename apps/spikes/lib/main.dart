/// Stride Phase 0 spike harness.
///
/// This app exists to answer the S1-S10 questions in docs/PLAN.md on the real console, and then be
/// thrown away. It is not a preview of Stride: there is no Control and Safety Coordinator here, and
/// nothing in this app may ever command the motor.
library;

import 'package:flutter/material.dart';

import 'screens/apps_screen.dart';
import 'screens/environment_screen.dart';
import 'screens/glassos_screen.dart';
import 'screens/launcher_screen.dart';
import 'screens/media_screen.dart';
import 'screens/navigation_screen.dart';
import 'screens/overlay_screen.dart';

void main() => runApp(const SpikeApp());

class SpikeApp extends StatelessWidget {
  const SpikeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Stride Spikes',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF00E5A0),
          brightness: Brightness.dark,
        ),
      ),
      home: const SpikeHome(),
    );
  }
}

class _Spike {
  const _Spike(this.id, this.title, this.question, this.icon, this.builder);

  final String id;
  final String title;
  final String question;
  final IconData icon;
  final WidgetBuilder builder;
}

const List<_Spike> _spikes = <_Spike>[
  _Spike(
    'ENV',
    'Environment',
    'What is this console, really?',
    Icons.memory,
    _envBuilder,
  ),
  _Spike(
    'S1',
    'Launcher replace + revert',
    'Can Stride become HOME, and can we get back to iFit?',
    Icons.home_outlined,
    _launcherBuilder,
  ),
  _Spike(
    'S2',
    'GlassOS certs + gRPC',
    'Can we extract the credentials on-device and complete an mTLS gRPC call?',
    Icons.vpn_key_outlined,
    _glassosBuilder,
  ),
  _Spike(
    'S3',
    'Overlay + edge gestures',
    'Does the overlay survive real apps, and how much touch does it steal?',
    Icons.layers_outlined,
    _overlayBuilder,
  ),
  _Spike(
    'S4',
    'Media apps present',
    'What actually installs and plays on a non-GMS console?',
    Icons.apps_outlined,
    _appsBuilder,
  ),
  _Spike(
    'S5',
    'MediaSession control',
    'Can we observe and pause/resume the apps that survived S4?',
    Icons.music_note_outlined,
    _mediaBuilder,
  ),
  _Spike(
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

class SpikeHome extends StatelessWidget {
  const SpikeHome({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Stride — Phase 0 spikes'),
        bottom: const PreferredSize(
          preferredSize: Size.fromHeight(28),
          child: Padding(
            padding: EdgeInsets.only(bottom: 8, left: 16, right: 16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Harness only. Nothing here commands the motor.',
                style: TextStyle(fontSize: 12),
              ),
            ),
          ),
        ),
      ),
      body: ListView.separated(
        padding: const EdgeInsets.all(12),
        itemCount: _spikes.length,
        separatorBuilder: (context, index) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          final spike = _spikes[i];
          return Card(
            clipBehavior: Clip.antiAlias,
            child: ListTile(
              leading: CircleAvatar(child: Icon(spike.icon)),
              title: Text('${spike.id} — ${spike.title}'),
              subtitle: Text(spike.question),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: spike.builder),
              ),
            ),
          );
        },
      ),
    );
  }
}
