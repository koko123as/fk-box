package io.nekohasekai.sfa.bridge

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun CoroutineScope.pollWhileVisible(
    visible: StateFlow<Boolean>,
    foreground: StateFlow<Boolean>,
    screenOn: StateFlow<Boolean>,
    intervalMillis: Long = 2000,
    onError: (Exception) -> Unit = { Log.w("VisiblePolling", "sample failed", it) },
    sample: suspend () -> Unit,
): Job = launch {
    combine(visible, foreground, screenOn) { requested, active, awake -> requested && active && awake }
        .distinctUntilChanged().collectLatest { active ->
            if (!active) return@collectLatest
            while (isActive) {
                // A failing sample is reported and retried at the next tick; thrown
                // out of this lifecycleScope job it would crash the app.
                try {
                    sample()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
                delay(intervalMillis)
            }
        }
}
