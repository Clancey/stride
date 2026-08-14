import 'package:flutter/material.dart';

import 'stride_tokens.dart';

class StrideTheme {
  const StrideTheme._();

  static ThemeData dark() {
    final scheme = ColorScheme.fromSeed(
      seedColor: StrideColors.accent,
      brightness: Brightness.dark,
      surface: StrideColors.panel,
      primary: StrideColors.accent,
      secondary: StrideColors.info,
      error: StrideColors.danger,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: StrideColors.ink,
      colorScheme: scheme,
      visualDensity: VisualDensity.standard,
      textTheme: const TextTheme(
        displayLarge: TextStyle(
          fontSize: 40,
          height: 1.02,
          fontWeight: FontWeight.w800,
          letterSpacing: -1.2,
          color: StrideColors.text,
        ),
        headlineLarge: TextStyle(
          fontSize: 34,
          height: 1.05,
          fontWeight: FontWeight.w800,
          letterSpacing: -0.8,
          color: StrideColors.text,
        ),
        headlineMedium: TextStyle(
          fontSize: 28,
          height: 1.1,
          fontWeight: FontWeight.w700,
          color: StrideColors.text,
        ),
        titleLarge: TextStyle(
          fontSize: 22,
          height: 1.15,
          fontWeight: FontWeight.w700,
          color: StrideColors.text,
        ),
        titleMedium: TextStyle(
          fontSize: 18,
          height: 1.2,
          fontWeight: FontWeight.w700,
          color: StrideColors.text,
        ),
        bodyLarge: TextStyle(
          fontSize: 18,
          height: 1.35,
          fontWeight: FontWeight.w500,
          color: StrideColors.text,
        ),
        bodyMedium: TextStyle(
          fontSize: 16,
          height: 1.35,
          fontWeight: FontWeight.w500,
          color: StrideColors.textMuted,
        ),
        bodySmall: TextStyle(
          fontSize: 14,
          height: 1.3,
          fontWeight: FontWeight.w600,
          color: StrideColors.textMuted,
        ),
        labelLarge: TextStyle(
          fontSize: 16,
          height: 1.1,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.1,
          color: StrideColors.text,
        ),
        labelMedium: TextStyle(
          fontSize: 14,
          height: 1.1,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.2,
          color: StrideColors.text,
        ),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: StrideColors.ink,
        foregroundColor: StrideColors.text,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontSize: 24,
          fontWeight: FontWeight.w800,
          color: StrideColors.text,
        ),
      ),
      cardTheme: CardThemeData(
        color: StrideColors.panelRaised,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          side: const BorderSide(color: StrideColors.line),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(StrideSpace.minTouch, StrideSpace.minTouch),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(StrideRadius.md),
          ),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: StrideColors.text,
          minimumSize: const Size(StrideSpace.minTouch, StrideSpace.minTouch),
          side: const BorderSide(color: StrideColors.line, width: 1.4),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(StrideRadius.md),
          ),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size(StrideSpace.minTouch, StrideSpace.minTouch),
          foregroundColor: StrideColors.text,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(StrideRadius.md),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: StrideColors.panelRaised,
        hintStyle: const TextStyle(color: StrideColors.textMuted, fontSize: 18),
        labelStyle: const TextStyle(
          color: StrideColors.textMuted,
          fontSize: 16,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: StrideSpace.lg,
          vertical: StrideSpace.md,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          borderSide: const BorderSide(color: StrideColors.line),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          borderSide: const BorderSide(color: StrideColors.line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          borderSide: const BorderSide(color: StrideColors.accent, width: 2),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: StrideColors.panelHigh,
        contentTextStyle: const TextStyle(
          color: StrideColors.text,
          fontSize: 16,
          fontWeight: FontWeight.w700,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(StrideRadius.md),
        ),
      ),
    );
  }
}
