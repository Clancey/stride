import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

class OverlayPrefs {
  OverlayPrefs({this.fileName = 'overlay_prefs.json'});

  final String fileName;

  Future<bool?> readEnabled() async {
    try {
      final file = await _file();
      if (!await file.exists()) return null;
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is Map<String, dynamic>) {
        final enabled = decoded['enabled'];
        return enabled is bool ? enabled : null;
      }
      return null;
    } on Object {
      return null;
    }
  }

  Future<void> writeEnabled(bool enabled) async {
    final file = await _file();
    await file.parent.create(recursive: true);
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(
      jsonEncode(<String, Object?>{'enabled': enabled}),
      flush: true,
    );
    await temp.rename(file.path);
  }

  Future<File> _file() async {
    final directory = await getApplicationDocumentsDirectory();
    final separator = Platform.pathSeparator;
    final base = directory.path.endsWith(separator)
        ? directory.path
        : '${directory.path}$separator';
    return File('$base$fileName');
  }
}
