package io.nekohasekai.sfa.compose.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * FlClash v0.9.3 lib/common/layout.dart (getViewMode, canExpandSidebar,
 * cardSpacingOf) and lib/common/constant.dart (maxMobileWidth 600,
 * maxLaptopWidth 840), GPL-3.0.
 */

/** enum.dart's ViewMode, read from the whole window's width. */
enum class ViewMode { Mobile, Laptop, Desktop }

const val MAX_MOBILE_WIDTH = 600f
const val MAX_LAPTOP_WIDTH = 840f

/** layout.dart's getViewMode. */
fun viewModeOf(width: Dp): ViewMode = when {
    width.value <= MAX_MOBILE_WIDTH -> ViewMode.Mobile
    width.value <= MAX_LAPTOP_WIDTH -> ViewMode.Laptop
    else -> ViewMode.Desktop
}

/** An expanded sidebar at laptop widths would leave the content narrower than the mobile layout. */
fun canExpandSidebar(mode: ViewMode): Boolean = mode == ViewMode.Desktop

/** layout.dart's cardSpacingOf: 8 / 12 / 16. */
fun cardSpacingOf(mode: ViewMode): Dp = when (mode) {
    ViewMode.Mobile -> 8.dp
    ViewMode.Laptop -> 12.dp
    ViewMode.Desktop -> 16.dp
}

/** viewModeProvider: set by the root navigator host from the window width. */
val LocalViewMode = staticCompositionLocalOf { ViewMode.Mobile }

/*
 * FlClash v0.9.3 lib/views/dashboard/layout.dart (DashboardDensity,
 * dashboardColumnsOf, dashboardMobileSideOf), GPL-3.0.
 */

/** [Compact] shrinks type, the start button and tiles, and drops subtitles. */
enum class DashboardDensity {
    Standard,
    Compact,
    Mobile;

    val isDesktop: Boolean get() = this != Mobile
    val isCompact: Boolean get() = this == Compact
}

private const val MIN_DESKTOP_WIDTH = 640f
private const val MIN_FOUR_COLUMN_WIDTH = 860f
private const val MIN_STANDARD_FOUR_COLUMN_WIDTH = 1040f
private const val MIN_STANDARD_HEIGHT = 824f
const val DASHBOARD_MOBILE_MAX_WIDTH = 560f

fun dashboardColumnsOf(width: Float): Int = if (width < MIN_FOUR_COLUMN_WIDTH) 2 else 4

/** The content room divided by the text scale, as layout.dart's DashboardDensity.of reads it. */
fun dashboardDensityOf(width: Float, height: Float): DashboardDensity {
    if (width < MIN_DESKTOP_WIDTH) return DashboardDensity.Mobile
    val narrowColumns = dashboardColumnsOf(width) == 4 && width < MIN_STANDARD_FOUR_COLUMN_WIDTH
    return if (narrowColumns || height < MIN_STANDARD_HEIGHT) DashboardDensity.Compact else DashboardDensity.Standard
}

/** DashboardDensityScope: the density and the text scale every fixed height grows by. */
val LocalDashboardDensity = staticCompositionLocalOf { DashboardDensity.Mobile }
val LocalDashboardScale = staticCompositionLocalOf { 1f }

/** widgets/card.dart's dashboardCardPadding: (horizontal, vertical). */
fun dashboardCardPadding(density: DashboardDensity): Pair<Dp, Dp> = when (density) {
    DashboardDensity.Standard -> 22.dp to 20.dp
    DashboardDensity.Compact -> 20.dp to 16.dp
    DashboardDensity.Mobile -> 20.dp to 18.dp
}

/** style.dart's DashboardCorner: desktop card 32, mobile card 34. */
fun dashboardCardRadius(density: DashboardDensity): Float = if (density.isDesktop) 32f else 34f
