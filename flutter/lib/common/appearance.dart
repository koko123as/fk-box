import 'dart:math' as math;

/// The "Style" appearance preference: the FlClash look, or a flat, quiet one.
enum AppearanceStyle {
  standard,
  minimal;

  static AppearanceStyle of(Object? value) =>
      values.firstWhere((style) => style.name == value, orElse: () => standard);
}

/// The app-wide appearance tokens. Glass, shadows, motion and press feedback
/// read these; colours, layouts and functional indicators are the same in every style.
class AppearanceTokens {
  const AppearanceTokens._({
    required this.style,
    required this.glass,
    required this.shadows,
    required this.decorations,
    required this.inkSpread,
    required this.sparkle,
    required this.elasticPress,
    required this.expressiveMotion,
    required this.fadeDuration,
    required this.cornerScale,
  });

  final AppearanceStyle style;
  final bool glass, shadows, decorations, inkSpread, sparkle, elasticPress, expressiveMotion;
  final Duration fadeDuration;
  final double cornerScale;

  bool get minimal => style == AppearanceStyle.minimal;

  static const _minCorner = 4.0;

  /// A corner radius for this style (small radii keep a readable floor).
  double corner(double radius) {
    if (cornerScale >= 1 || radius <= 0 || radius >= 1000) return radius;
    return math.max(radius * cornerScale, math.min(radius, _minCorner));
  }

  static const standard = AppearanceTokens._(
    style: AppearanceStyle.standard, glass: true, shadows: true, decorations: true, inkSpread: true,
    sparkle: true, elasticPress: true, expressiveMotion: true,
    fadeDuration: Duration(milliseconds: 160), cornerScale: 1,
  );

  static const minimal_ = AppearanceTokens._(
    style: AppearanceStyle.minimal, glass: false, shadows: false, decorations: false, inkSpread: false,
    sparkle: false, elasticPress: false, expressiveMotion: false,
    fadeDuration: Duration(milliseconds: 160), cornerScale: .5,
  );

  static AppearanceTokens of(AppearanceStyle style) => style == AppearanceStyle.minimal ? minimal_ : standard;
}

AppearanceTokens appearanceTokensOf(Map<String, dynamic> preferences) =>
    AppearanceTokens.of(AppearanceStyle.of(preferences['appearanceStyle']));
