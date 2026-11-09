/// FlClash v0.9.3 lib/common/constant.dart breakpoints.
const maxMobileWidth = 600.0;
const maxLaptopWidth = 840.0;

/// FlClash v0.9.3 lib/enum/enum.dart ViewMode.
enum ViewMode { mobile, laptop, desktop }

/// FlClash v0.9.3 lib/common/layout.dart getViewMode.
ViewMode viewModeOf(double width) {
  if (width <= maxMobileWidth) return ViewMode.mobile;
  if (width <= maxLaptopWidth) return ViewMode.laptop;
  return ViewMode.desktop;
}

/// An expanded sidebar in the laptop range would leave the content narrower
/// than the mobile layout one breakpoint below, so labels wait for desktop.
bool canExpandSidebar(ViewMode viewMode) => viewMode == ViewMode.desktop;

/// Card spacing 8/12/16 for mobile, laptop and desktop widths.
double cardSpacingFor(ViewMode viewMode) => switch (viewMode) {
  ViewMode.mobile => 8.0,
  ViewMode.laptop => 12.0,
  ViewMode.desktop => 16.0,
};

// FlClash v0.9.3 lib/views/dashboard/layout.dart (GPL-3.0).
const _minDesktopDashboardWidth = 640.0;
const _minFourColumnWidth = 860.0;
const _minStandardFourColumnWidth = 1040.0;
const _minStandardHeight = 824.0;
const dashboardMobileMaxWidth = 560.0;

int dashboardColumnsOf(double width) => width < _minFourColumnWidth ? 2 : 4;

/// [compact] shrinks type, the start button and tiles, and drops subtitles.
enum DashboardDensity {
  standard,
  compact,
  mobile;

  bool get isDesktop => this != mobile;
  bool get isCompact => this == compact;

  /// From the content room divided by the text scale.
  static DashboardDensity of(double width, double height) {
    if (width < _minDesktopDashboardWidth) return mobile;
    final narrowColumns = dashboardColumnsOf(width) == 4 && width < _minStandardFourColumnWidth;
    return narrowColumns || height < _minStandardHeight ? compact : standard;
  }
}

/// widgets/card.dart's dashboardCardPadding: (horizontal, vertical).
(double, double) dashboardCardPadding(DashboardDensity density) => switch (density) {
  DashboardDensity.standard => (22.0, 20.0),
  DashboardDensity.compact => (20.0, 16.0),
  DashboardDensity.mobile => (20.0, 18.0),
};

/// style.dart's DashboardCorner: desktop card 32, mobile card 34.
double dashboardCardRadius(DashboardDensity density) => density.isDesktop ? 32 : 34;
