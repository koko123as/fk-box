// Ported from FlClash v0.9.3 (android/common/.../Ext.kt), GPL-3.0. FK-box drops the
// service-side helpers (its own BoxService owns the notification).
package com.follow.clash.common

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.reflect.KClass

val KClass<*>.intent: Intent
    get() = Intent(GlobalState.application, this.java)

val ComponentName.intent: Intent
    get() = Intent().apply {
        component = this@intent
    }

val QuickAction.action: String
    get() = "${GlobalState.application.packageName}.action.${this.name}"

val QuickAction.shortcutId: String
    get() = name.lowercase()

val QuickAction.quickIntent: Intent
    get() = Components.quickActionActivity.intent.apply {
        action = this@quickIntent.action
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

val Intent.toPendingIntent: PendingIntent
    get() = PendingIntent.getActivity(
        GlobalState.application,
        0,
        this,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun Context.registerReceiverCompat(
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    exported: Boolean = false,
) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(
        receiver,
        filter,
        if (exported) RECEIVER_EXPORTED else RECEIVER_NOT_EXPORTED,
    )
} else {
    registerReceiver(receiver, filter)
}

fun Context.receiveBroadcastFlow(
    exported: Boolean = false,
    configure: IntentFilter.() -> Unit,
): Flow<Intent> = callbackFlow {
    val filter = IntentFilter().apply(configure)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            trySend(intent)
        }
    }
    registerReceiverCompat(receiver, filter, exported)
    awaitClose {
        // Cancellation reaches here on another thread, so a Service whose
        // onDestroy cancelled the collector may already have had the system
        // drop its leaked receivers, and unregistering again then throws.
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
