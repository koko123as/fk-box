import 'package:flutter/widgets.dart';
import 'package:prism_ui/input_border.dart';

abstract final class AppCorner {
  static const double none = 0;
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 16;
  static const double lg = 20;
  static const double xl = 24;
  static const double xxl = 28;

  /// UIAlertController's corner on iOS 27, rounder than a sheet's.
  static const double alert = 34;
  static const double full = 1000;

  static double fit(double shortestSide) {
    final limit = shortestSide / 3;
    if (limit >= xxl) return xxl;
    if (limit >= xl) return xl;
    if (limit >= lg) return lg;
    if (limit >= md) return md;
    if (limit >= sm) return sm;
    if (limit >= xs) return xs;
    return none;
  }
}

/// App-wide style switches the host sets: fk-box's minimal style scales
/// corners down, drops shadows for hairlines and presses with a static dim.
abstract final class PrismStyle {
  static const _minCorner = 4.0;

  /// Corner radii from [AppRadius] and [AppShape] are multiplied by this.
  static double cornerScale = 1;

  /// Drop shadows; without them a raised surface takes a hairline outline.
  static bool shadows = true;

  /// Ink that spreads in and out; without it a press is a static dim.
  static bool inkSpread = true;

  /// The press sparkle from the touch point.
  static bool sparkle = true;

  /// ElasticPress swells a pressed control.
  static bool elasticPress = true;

  /// The outline-variant colour a raised surface's hairline takes when there
  /// are no [shadows]; the host sets it with its colour scheme.
  static Color hairlineColor = const Color(0x1F000000);

  /// A raised surface's edge: nothing while it casts shadows, else a hairline.
  static BorderSide get hairline =>
      shadows ? BorderSide.none : BorderSide(color: hairlineColor, width: 0);

  /// [list] while the style casts shadows, else none.
  static List<BoxShadow> shadowsOr(List<BoxShadow> list) =>
      shadows ? list : const [];

  /// A corner radius for the style; small radii keep a readable floor.
  static double corner(double radius) {
    if (cornerScale >= 1 || radius <= 0 || radius >= AppCorner.full) return radius;
    return radius * cornerScale < _minCorner ? (radius < _minCorner ? radius : _minCorner) : radius * cornerScale;
  }
}

abstract final class AppRadius {
  static const BorderRadius none = BorderRadius.zero;
  static BorderRadius get xs => all(AppCorner.xs);
  static BorderRadius get sm => all(AppCorner.sm);
  static BorderRadius get md => all(AppCorner.md);
  static BorderRadius get lg => all(AppCorner.lg);
  static BorderRadius get xl => all(AppCorner.xl);
  static BorderRadius get xxl => all(AppCorner.xxl);
  static const BorderRadius full = BorderRadius.all(
    Radius.circular(AppCorner.full),
  );

  static BorderRadius all(double corner) =>
      BorderRadius.circular(PrismStyle.corner(corner));

  static BorderRadius top(double corner) =>
      BorderRadius.vertical(top: Radius.circular(PrismStyle.corner(corner)));

  static BorderRadius vertical({
    double top = AppCorner.none,
    double bottom = AppCorner.none,
  }) => BorderRadius.vertical(
    top: Radius.circular(PrismStyle.corner(top)),
    bottom: Radius.circular(PrismStyle.corner(bottom)),
  );
}

abstract final class AppShape {
  static const RoundedSuperellipseBorder none = RoundedSuperellipseBorder();
  static RoundedSuperellipseBorder get xs => of(AppRadius.xs);
  static RoundedSuperellipseBorder get sm => of(AppRadius.sm);
  static RoundedSuperellipseBorder get md => of(AppRadius.md);
  static RoundedSuperellipseBorder get lg => of(AppRadius.lg);
  static RoundedSuperellipseBorder get xl => of(AppRadius.xl);
  static RoundedSuperellipseBorder get xxl => of(AppRadius.xxl);
  static const RoundedSuperellipseBorder full = RoundedSuperellipseBorder(
    borderRadius: AppRadius.full,
  );
  static const CircleBorder circle = CircleBorder();
  static AppInputBorder get input => AppInputBorder(borderRadius: AppRadius.md);

  static RoundedSuperellipseBorder all(double corner) =>
      RoundedSuperellipseBorder(borderRadius: AppRadius.all(corner));

  static RoundedSuperellipseBorder top(double corner) =>
      RoundedSuperellipseBorder(borderRadius: AppRadius.top(corner));

  static RoundedSuperellipseBorder vertical({
    double top = AppCorner.none,
    double bottom = AppCorner.none,
  }) => RoundedSuperellipseBorder(
    borderRadius: AppRadius.vertical(top: top, bottom: bottom),
  );

  static RoundedSuperellipseBorder of(BorderRadius borderRadius) =>
      RoundedSuperellipseBorder(borderRadius: borderRadius);
}

class AppInputBorder extends InputBorder {
  const AppInputBorder({
    super.borderSide = const BorderSide(),
    this.borderRadius = const BorderRadius.all(Radius.circular(AppCorner.md)),
  });

  final BorderRadius borderRadius;

  RoundedSuperellipseBorder get _shape =>
      RoundedSuperellipseBorder(borderRadius: borderRadius, side: borderSide);

  @override
  bool get isOutline => true;

  @override
  bool get preferPaintInterior => true;

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(borderSide.width);

  @override
  AppInputBorder copyWith({
    BorderSide? borderSide,
    BorderRadius? borderRadius,
  }) {
    return AppInputBorder(
      borderSide: borderSide ?? this.borderSide,
      borderRadius: borderRadius ?? this.borderRadius,
    );
  }

  @override
  AppInputBorder scale(double t) {
    return AppInputBorder(
      borderSide: borderSide.scale(t),
      borderRadius: borderRadius * t,
    );
  }

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      _shape.getInnerPath(rect, textDirection: textDirection);

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) =>
      _shape.getOuterPath(rect, textDirection: textDirection);

  @override
  void paintInterior(
    Canvas canvas,
    Rect rect,
    Paint paint, {
    TextDirection? textDirection,
  }) => _shape.paintInterior(canvas, rect, paint, textDirection: textDirection);

  @override
  ShapeBorder? lerpFrom(ShapeBorder? a, double t) {
    if (a is AppInputBorder) {
      return AppInputBorder(
        borderSide: BorderSide.lerp(a.borderSide, borderSide, t),
        borderRadius: BorderRadius.lerp(a.borderRadius, borderRadius, t)!,
      );
    }
    return super.lerpFrom(a, t);
  }

  @override
  ShapeBorder? lerpTo(ShapeBorder? b, double t) {
    if (b is AppInputBorder) {
      return AppInputBorder(
        borderSide: BorderSide.lerp(borderSide, b.borderSide, t),
        borderRadius: BorderRadius.lerp(borderRadius, b.borderRadius, t)!,
      );
    }
    return super.lerpTo(b, t);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) =>
      _shape.paint(canvas, rect, textDirection: textDirection);
}
