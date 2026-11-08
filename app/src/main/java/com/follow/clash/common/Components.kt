// Ported from FlClash v0.9.3 (android/common/.../Components.kt), GPL-3.0.
// FK-box: the channel prefix stays com.follow.clash (lib/common/constant.dart
// packageName); the activities are FK-box's own components.
package com.follow.clash.common

import android.content.ComponentName

object Components {
    const val PACKAGE_NAME = "com.follow.clash"

    val mainActivity =
        ComponentName(GlobalState.packageName, "io.nekohasekai.sfa.compose.MainActivity")

    val quickActionActivity =
        ComponentName(GlobalState.packageName, "${PACKAGE_NAME}.QuickActionActivity")
}
