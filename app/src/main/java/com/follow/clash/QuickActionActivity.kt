// Based on FlClash v0.9.3 android/app/.../QuickActionActivity.kt (GPL-3.0).
// FK-box: start/stop go to BoxService; a start that needs the VPN prompt or a
// profile opens the main activity instead of FlClash's ServiceState.
package com.follow.clash

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState
import com.follow.clash.common.QuickAction
import com.follow.clash.common.action
import com.follow.clash.common.intent
import com.follow.clash.common.shortcutId
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    // A caller that repeats an action before this instance is gone reaches it here, not onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val action = QuickAction.entries.firstOrNull { it.action == intent.action } ?: return
        ShortcutManagerCompat.reportShortcutUsed(this, action.shortcutId)
        val running = FkHost.status.value == Status.Started || FkHost.status.value == Status.Starting
        when (action) {
            QuickAction.START -> if (!running) start()
            QuickAction.STOP -> if (running) BoxService.stop()
            QuickAction.TOGGLE -> if (running) BoxService.stop() else start()
        }
    }

    private fun start() {
        val app = GlobalState.application
        GlobalState.launch(Dispatchers.Main) {
            val ready = withContext(Dispatchers.IO) {
                Settings.dataStore.initialize()
                Settings.rebuildServiceMode()
                Settings.selectedProfile != -1L &&
                    (Settings.serviceMode != ServiceMode.VPN || VpnService.prepare(app) == null)
            }
            if (ready) {
                ContextCompat.startForegroundService(app, Intent(app, Settings.serviceClass()))
                Settings.startedByUser = true
            } else {
                app.startActivity(Components.mainActivity.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
