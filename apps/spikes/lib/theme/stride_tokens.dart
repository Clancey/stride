import 'package:flutter/material.dart';

class StrideColors {
  const StrideColors._();

  static const Color ink = Color(0xFF05070A);
  static const Color panel = Color(0xFF101820);
  static const Color panelRaised = Color(0xFF17232D);
  static const Color panelHigh = Color(0xFF20313D);
  static const Color line = Color(0xFF3B5363);
  static const Color text = Color(0xFFF4FBFF);
  static const Color textMuted = Color(0xFFB8CDD9);
  static const Color accent = Color(0xFF23F2A6);
  static const Color accentStrong = Color(0xFF00C982);
  static const Color warning = Color(0xFFFFC95A);
  static const Color danger = Color(0xFFFF6B6B);
  static const Color info = Color(0xFF67B7FF);

  static const List<Color> appFallbacks = <Color>[
    Color(0xFF23F2A6),
    Color(0xFF67B7FF),
    Color(0xFFFFC95A),
    Color(0xFFFF7A90),
    Color(0xFFB692FF),
    Color(0xFF61E4FF),
  ];
}

class StrideSpace {
  const StrideSpace._();

  static const double xxs = 4;
  static const double xs = 8;
  static const double sm = 12;
  static const double md = 16;
  static const double lg = 24;
  static const double xl = 32;
  static const double xxl = 48;
  static const double minTouch = 72;
  static const double primaryTouch = 96;
}

class StrideRadius {
  const StrideRadius._();

  static const double sm = 12;
  static const double md = 18;
  static const double lg = 26;
  static const double xl = 34;
}

class StrideMotion {
  const StrideMotion._();

  static const Duration quick = Duration(milliseconds: 160);
  static const Duration standard = Duration(milliseconds: 220);
}
