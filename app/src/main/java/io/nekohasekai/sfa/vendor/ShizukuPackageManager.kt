package io.nekohasekai.sfa.vendor

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuPackageManager {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val _shizukuInstalled = MutableStateFlow(false)
    val shizukuInstalled: StateFlow<Boolean> = _shizukuInstalled

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        ShizukuPrivilegedServiceClient.reset()
    }

    fun registerListeners() {
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    fun isShizukuInstalled(): Boolean = try {
        Application.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun unregisterListeners() {
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    fun isAvailable(): Boolean = ShizukuAccess.isAvailable()

    fun checkPermission(): Boolean = ShizukuAccess.checkPermission()

    fun requestPermission() = ShizukuAccess.requestPermission()

    fun refresh() {
        _shizukuInstalled.value = isShizukuInstalled()
    }

    suspend fun getInstalledPackages(flags: Int): List<PackageInfo> = withContext(Dispatchers.IO) {
        val service = ShizukuPrivilegedServiceClient.getService()
        val userId = Process.myUserHandle().hashCode()
        val slice = service.getInstalledPackages(flags, userId)
        @Suppress("UNCHECKED_CAST")
        slice.list as List<PackageInfo>
    }
}
