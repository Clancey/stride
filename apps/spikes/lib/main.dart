/// Stride launcher shell for the Phase 0 spike app.
library;

import 'package:flutter/material.dart';

import 'bridge.dart';
import 'screens/launcher_home.dart';
import 'theme/stride_theme.dart';
import 'widgets/hud_inset.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SpikeBridge.install();
  runApp(const SpikeApp());
}

class SpikeApp extends StatefulWidget {
  const SpikeApp({super.key});

  @override
  State<SpikeApp> createState() => _SpikeAppState();
}

class _SpikeAppState extends State<SpikeApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final GlobalKey<LauncherHomeState> _launcherKey =
      GlobalKey<LauncherHomeState>();

  @override
  void initState() {
    super.initState();
    SpikeBridge.onHomePressed = _returnToLauncherRoot;
  }

  @override
  void dispose() {
    if (SpikeBridge.onHomePressed == _returnToLauncherRoot) {
      SpikeBridge.onHomePressed = null;
    }
    super.dispose();
  }

  void _returnToLauncherRoot() {
    _navigatorKey.currentState?.popUntil((route) => route.isFirst);
    _launcherKey.currentState?.resetToLauncherRoot();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      title: 'Stride',
      debugShowCheckedModeBanner: false,
      theme: StrideTheme.dark(),
      builder: (context, child) =>
          HudInset(child: child ?? const SizedBox.shrink()),
      home: LauncherHome(key: _launcherKey),
    );
  }
}
