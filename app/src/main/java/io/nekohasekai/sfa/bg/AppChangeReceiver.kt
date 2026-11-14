package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.vendor.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")
        // Connection owners resolve uid → packages from a cache; any package
        // change can change that mapping.
        UidPackageCache.clear()
        // Removal only invalidates the cache; the managed per-app list is
        // rescanned on add/replace as before.
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Settings.perAppProxyEnabled) {
                    Log.d(TAG, "per app proxy disabled")
                    return@launch
                }
                if (!Settings.perAppProxyManagedMode) {
                    Log.d(TAG, "managed mode disabled")
                    return@launch
                }
                val packageName = intent.data?.schemeSpecificPart
                if (packageName.isNullOrEmpty()) rescanAllApps() else rescanPackage(packageName)
                Settings.dataStore.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rescan apps", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.error_title, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Only the added or replaced package can change the managed list: scan it
     * alone instead of every installed package (which, with dex scanning,
     * read every APK on each install).
     */
    private suspend fun rescanPackage(packageName: String) {
        val matches = PerAppProxyScanner.scanPackage(packageName) ?: return
        val current = Settings.perAppProxyManagedList
        val next = if (matches) current + packageName else current - packageName
        if (next != current) Settings.perAppProxyManagedList = next
        Log.d(TAG, "rescanned $packageName: ${if (matches) "managed" else "not managed"}")
    }

    private suspend fun rescanAllApps() {
        Log.d(TAG, "rescanning all apps")
        val chinaApps = PerAppProxyScanner.scanAllChinaApps()
        Settings.perAppProxyManagedList = chinaApps
        Log.d(TAG, "rescan complete, found ${chinaApps.size} china apps")
    }
}
