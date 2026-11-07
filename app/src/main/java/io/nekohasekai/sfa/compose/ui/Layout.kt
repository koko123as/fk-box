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
