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
