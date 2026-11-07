package io.nekohasekai.sfa.compose.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.common.IpProbeCancelled
import io.nekohasekai.sfa.compose.common.IpQualityCache
import io.nekohasekai.sfa.compose.common.IpQualityLookupException
import io.nekohasekai.sfa.compose.common.RoutedIpProbe
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.alphaBlend
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.BoxPanel
import io.nekohasekai.sfa.compose.ui.ChipTone
import io.nekohasekai.sfa.compose.ui.DashboardChip
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.MaterialShapes
import io.nekohasekai.sfa.compose.ui.shapePathOf
import io.nekohasekai.sfa.compose.ui.PanelShape
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.ShapeBadge
import io.nekohasekai.sfa.compose.ui.SkeletonPill
import io.nekohasekai.sfa.compose.ui.SkeletonText
import io.nekohasekai.sfa.compose.ui.elasticPress
import io.nekohasekai.sfa.compose.ui.semanticsLabel
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * components/status_panel.dart (mobile layout from FlClash's
 * current_outbound.dart): run state, start toggle, the routed outbound IP with
 * its country, type and quality, run time and organization. The refresh state
 * machine (route stamp, 5 s throttle, 300 ms settle, quality expiry, manual
 * retry) is translated as is.
 */

private const val THROTTLE_MILLIS = 5000L
private const val MOVE_SETTLE_MILLIS = 300L

private class StatusModel(private val box: BoxController, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var qualityChecking by mutableStateOf(false)
    var egress by mutableStateOf<Map<String, Any?>?>(null)
    var error by mutableStateOf<String?>(null)
    var qualityError by mutableStateOf<String?>(null)
    var routeChanged by mutableStateOf(false)
    var ipDue by mutableStateOf(true)
    var detailsRevision by mutableIntStateOf(0)
    private var stamp: String? = null
    private var probe: RoutedIpProbe? = null
    private var qualityExpiry: Job? = null
    private var cooldown: Job? = null
    private var settle: Job? = null
    private var generation = 0
    var foreground = true
    private var qualityDue = false
    private var manual = false

    fun onResume() {
        foreground = true
        if (!checking && (egress == null || error != null)) ipDue = true
        scheduleRefresh()
    }

    fun onPause() {
        foreground = false
    }

    fun syncRoute(next: String) {
        if (stamp == next) return
        val changed = stamp != null
        stamp = next
        generation++
        probe?.cancel()
        probe = null
        qualityExpiry?.cancel()
        egress = null
        error = null
        qualityError = null
        checking = false
        qualityChecking = false
        ipDue = true
        qualityDue = false
        manual = false
        routeChanged = changed
        if (changed && settle == null) {
            settle = scope.launch {
                delay(MOVE_SETTLE_MILLIS)
                settle = null
                scheduleRefresh()
            }
        }
        scheduleRefresh()
    }

    fun scheduleRefresh() {
        scope.launch { flushRefresh() }
    }

    fun retry() {
        if (checking || qualityChecking) return
        ipDue = true
        manual = true
        scheduleRefresh()
    }

    private fun flushRefresh() {
        if (!foreground || checking || qualityChecking || box.transitioning || box.remote) return
        if (ipDue && !manual && (settle != null || (!routeChanged && cooldown != null))) return
        if (ipDue || qualityDue) scope.launch { refresh(readIp = ipDue, manual = manual) }
    }

    private suspend fun refresh(readIp: Boolean, manual: Boolean) {
        val retryQualityFor = if (manual && qualityError != null) egress?.get("ip") else null
        ipDue = false
        qualityDue = false
        this.manual = false
        routeChanged = false
        if (readIp) {
            cooldown?.cancel()
            cooldown = scope.launch {
                delay(THROTTLE_MILLIS)
                cooldown = null
                scheduleRefresh()
            }
        }
        val current = ++generation
        qualityExpiry?.cancel()
        val next = RoutedIpProbe(box)
        probe?.cancel()
        probe = next
        checking = readIp
        qualityChecking = !readIp
        error = null
        qualityError = null
        try {
            val result: MutableMap<String, Any?> = if (readIp) next.egress().toMutableMap() else (egress ?: emptyMap()).toMutableMap()
            if (current != generation) return
            val ip = result["ip"] as? String
            egress = if (result.isEmpty()) null else result.toMap()
            checking = false
            qualityChecking = ip != null
            if (ip == null) return
            try {
                if (!readIp) next.open()
                val record = box.ipQualities.lookup(next, ip, refresh = !readIp || ip == retryQualityFor)
                if (current != generation) return
                result.remove("qualityError")
                result.remove("qualityFailures")
                result.putAll(record.quality.toDashboard(record.checkedAt))
                egress = result.toMap()
                qualityChecking = false
                detailsRevision++
                val remaining = record.checkedAt + IpQualityCache.MAX_AGE - System.currentTimeMillis()
                qualityExpiry = scope.launch {
                    delay(max(0L, remaining))
                    if (current == generation) {
                        qualityDue = true
                        scheduleRefresh()
                    }
                }
            } catch (cancelled: IpProbeCancelled) {
                // Superseded by a newer route.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (lookup: Exception) {
                if (current == generation) {
                    qualityChecking = false
                    qualityError = BoxController.describe(lookup)
                    result["qualityError"] = qualityError
                    if (lookup is IpQualityLookupException) {
                        result["qualityFailures"] = lookup.failures.map { mapOf("source" to it.source.label, "status" to it.status.name) }
                    }
                    egress = result.toMap()
                    detailsRevision++
                }
            }
        } catch (cancelled: IpProbeCancelled) {
            // Superseded by a newer route.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current == generation) {
                egress = null
                error = BoxController.describe(failure)
            }
        } finally {
            next.close()
            if (current == generation) {
                checking = false
                qualityChecking = false
                probe = null
                if (ipDue || qualityDue) scheduleRefresh()
            }
        }
    }

    fun dispose() {
        generation++
        probe?.cancel()
        qualityExpiry?.cancel()
        cooldown?.cancel()
        settle?.cancel()
    }
}

/** current_outbound.dart's _OutboundMetrics per density. */
private class OutboundMetrics(
    val padding: PaddingValues,
    val gap: Float,
    val ipTop: Float,
    val cellGap: Float,
    val badgeSize: Float,
    val ip: AppTextStyle,
    val buttonSize: Float,
    val buttonSlot: Float,
    val decorationSize: Float,
    val decorationEnd: Float,
    val decorationTop: Float,
)

private fun outboundMetricsOf(density: DashboardDensity): OutboundMetrics = when (density) {
    DashboardDensity.Standard -> OutboundMetrics(dashboardCardPadding(density), 16f, 10f, 28f, 48f, AppTextStyle.Title1, 104f, 120f, 290f, -64f, -87f)
    DashboardDensity.Compact -> OutboundMetrics(dashboardCardPadding(density), 16f, 8f, 22f, 40f, AppTextStyle.Title2, 84f, 100f, 250f, -64f, -75f)
    DashboardDensity.Mobile -> OutboundMetrics(PaddingValues(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp), 10f, 12f, 24f, 48f, AppTextStyle.Title2, 88f, 88f, 240f, -60f, -66f)
}

@Composable
fun StatusPanel(box: BoxController, modifier: Modifier = Modifier) {
    val scheme = BoxTheme.scheme
    val scope = rememberCoroutineScope()
    val model = remember { StatusModel(box, scope) }
    val navigator = LocalBoxNavigator.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.onResume()
                Lifecycle.Event.ON_PAUSE -> model.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.dispose()
        }
    }
    // _syncRoute: route changes cancel stale requests.
    val selections = box.groups.map { "${it["tag"]}:${it["selected"]}" }.sorted()
    val addresses = box.lanAddresses.sorted()
    val route = if (box.running) {
        "${box.dashboard["startedAt"]}:${box.selectedProfileId}:${box.dashboard["mode"]?.toString()?.lowercase()}:${selections.joinToString(",")}"
    } else {
        ""
    }
    val stamp = "${box.dashboard["status"]}:${box.running}:${box.remote}:${box.dashboard["remote"]}:$route:${box.lan["stamp"]}:$addresses"
    LaunchedEffect(stamp) { model.syncRoute(stamp) }
    LaunchedEffect(box.transitioning, box.remote) { model.scheduleRefresh() }

    val status = if (box.transitioning) box.dashboard["status"].toString() else if (box.running) "Running" else "Not running"
    val info = model.egress
    val ip = info?.get("ip") as? String
    val foreground = if (box.running) scheme.onSurface else scheme.onSurfaceVariant
    val scale = max(1f, LocalDensity.current.fontScale)
    val density = dashboardDensity
    val metrics = outboundMetricsOf(density)
    val ipStyle = metrics.ip.of(true).tabular.copy(color = foreground, letterSpacing = 0.sp)
    val ipLoading = model.checking || (model.routeChanged && model.ipDue && !box.transitioning && !box.remote)
    val loading = (ipLoading && ip == null) || model.qualityChecking
    val organization = (info?.get("organization") as? String)?.takeIf { it.isNotEmpty() } ?: info?.get("asn") as? String
    val badgeProgress by animateFloatAsState(if (box.running) 1f else 0f, tween(450, easing = EaseOutBack), label = "badge")

    val cardShape = io.nekohasekai.sfa.compose.theme.AppShape.all(dashboardCardRadius(density))
    DashboardCard(modifier, padding = PaddingValues(0.dp), semanticLabel = tr("Status")) {
        Box(if (density.isDesktop) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            // The cookie behind the top-end corner, cut to the card's squircle. It used to be a
            // ShapeBadge under a squircle clip; the cut is now the path intersection itself, a
            // plain fill rebuilt only while the badge animates, so HWUI keeps no clip mask.
            val decorationColor = lerp(SurfaceLevel.Block.colorOf(scheme), alphaBlend(scheme.primary.copy(alpha = .1f), SurfaceLevel.Card.colorOf(scheme)), badgeProgress.coerceIn(0f, 1f))
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val turn = badgeProgress
                    val side = metrics.decorationSize.dp.toPx()
                    // Where Alignment.TopEnd + wrapContentSize(unbounded) + offset put the badge.
                    val shiftX = (-metrics.decorationEnd).dp.toPx()
                    val left = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) size.width - side + shiftX else -shiftX
                    val top = metrics.decorationTop.dp.toPx()
                    val growth = 1 + .12f * turn
                    val cookie = androidx.compose.ui.graphics.Path().apply {
                        addPath(shapePathOf(MaterialShapes.Cookie12Sided))
                        transform(
                            androidx.compose.ui.graphics.Matrix().apply {
                                // The badge's graphicsLayer turn and swell about its center, then the unit square scaled to its size.
                                translate(left + side / 2, top + side / 2)
                                rotateZ((180f / 6f) * turn)
                                scale(growth, growth)
                                translate(-side / 2, -side / 2)
                                scale(side, side)
                            },
                        )
                    }
                    val card = androidx.compose.ui.graphics.Path().apply { addOutline(cardShape.createOutline(size, layoutDirection, this@drawWithCache)) }
                    val cut = androidx.compose.ui.graphics.Path().apply { op(cookie, card, androidx.compose.ui.graphics.PathOperation.Intersect) }
                    onDrawBehind { drawPath(cut, decorationColor) }
                },
            )
            Row(
                (if (density.isDesktop) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(metrics.padding),
                horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
                    DashboardCardHeader(
                        title = tr("Status"),
                        glyph = AppGlyphs.status,
                        leading = { DashboardChip(tr(status), tone = if (box.running) ChipTone.Ok else ChipTone.Idle, dot = box.running) },
                    )
                    Spacer(Modifier.height(metrics.ipTop.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CountryBadge(
                            size = metrics.badgeSize,
                            country = info?.get("country") as? String,
                            active = box.running,
                            checking = ipLoading || model.qualityChecking,
                            onRefresh = if (!box.transitioning && !box.remote && !model.checking && !model.qualityChecking) model::retry else null,
                        )
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height((metrics.ip.leading * scale).dp), contentAlignment = Alignment.CenterStart) {
                                when {
                                    ip != null -> BasicText(
                                        ip,
                                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                            navigator?.openForm { EgressDetails(model) }
                                        },
                                        style = ipStyle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // FlClash stops shrinking at 75% so a long IPv6 address stays readable.
                                        autoSize = TextAutoSize.StepBased(minFontSize = (metrics.ip.size * .75f).sp, maxFontSize = metrics.ip.size.sp, stepSize = 0.5.sp),
                                    )
                                    ipLoading -> SkeletonText(140.dp, ipStyle)
                                    else -> Text(
                                        if (model.error == null) "—" else tr("Check failed"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = ipStyle.copy(color = if (model.error == null) foreground else scheme.error),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(28.dp).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                                if (model.error != null && ip == null) {
                                    Text(tr("Tap icon to retry"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = statusLabelStyle())
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (loading) {
                                            SkeletonPill(48.dp, 28.dp)
                                            SkeletonPill(72.dp, 28.dp)
                                        } else {
                                            DashboardChip(tr(info?.get("type") as? String ?: "Unknown type"), large = !density.isDesktop)
                                            val quality = info?.get("quality") as? String
                                            if (quality != null) {
                                                DashboardChip(
                                                    "${tr("Quality")} ${tr(quality)}",
                                                    large = !density.isDesktop,
                                                    tone = when (quality) {
                                                        "Good" -> ChipTone.Ok
                                                        "Risky" -> ChipTone.Warn
                                                        else -> ChipTone.Idle
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (density.isDesktop) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(metrics.cellGap.dp)) {
                        StatusCell("Run time") { RuntimeValue(if (box.running) box.dashboard["startedAt"].asLong() else null) }
                        Box(Modifier.weight(1f, fill = false)) {
                            StatusCell("Organization") {
                                when {
                                    organization != null -> Text(organization, maxLines = 1, overflow = TextOverflow.Ellipsis, style = statusCellStyle())
                                    loading -> SkeletonText(96.dp, statusCellStyle())
                                    else -> Text("—", maxLines = 1, style = statusCellStyle())
                                }
                            }
                        }
                    }
                }
                Box(Modifier.width(metrics.buttonSlot.dp), contentAlignment = Alignment.Center) {
                    if (box.remote) {
                        GlyphIcon(AppGlyphs.proxies, size = 60.dp)
                    } else {
                        StartToggle(
                            size = metrics.buttonSize.dp,
                            enabled = (box.running || box.selectedProfile != null) && !model.busy && !box.transitioning,
                            running = box.running,
                            suspended = false,
                        ) {
                            if (model.busy || box.transitioning) return@StartToggle
                            if (!box.running && box.selectedProfile == null) {
                                navigator?.openForm { AddProfilePage(box) }
                                return@StartToggle
                            }
                            model.busy = true
                            box.launchAct(if (box.running) "service.stop" else "service.start") { model.busy = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabelStyle() = AppTextStyle.Caption1.of().tabular.copy(color = BoxTheme.scheme.onSurfaceVariant)

@Composable
private fun statusCellStyle() = AppTextStyle.Subheadline.of(true).tabular.copy(color = BoxTheme.scheme.onSurface)

@Composable
private fun StatusCell(label: String, content: @Composable () -> Unit) {
    Column {
        Text(tr(label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = statusLabelStyle())
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun RuntimeValue(startedAt: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = if (startedAt == null) {
        "—"
    } else {
        val seconds = min(999L * 3600 + 59 * 60 + 59, max(0L, (now - startedAt) / 1000))
        listOf(seconds / 3600, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
    }
    Text(text, maxLines = 1, style = statusCellStyle())
}

@Composable
private fun CountryBadge(size: Float, country: String?, active: Boolean, checking: Boolean, onRefresh: (() -> Unit)?) {
    val scheme = BoxTheme.scheme
    val color = if (active) scheme.onSurface else scheme.onSurfaceVariant
    val turn = remember { Animatable(0f) }
    LaunchedEffect(checking) {
        if (checking) {
            while (true) {
                turn.animateTo(turn.value + 1f, tween(1500, easing = LinearEasing))
            }
        } else {
            val rest = ceil(turn.value * 4) / 4
            turn.animateTo(rest, tween(max(0, (1500 * (rest - turn.value)).toInt()), easing = LinearEasing))
        }
    }
    Box(
        Modifier
            .elasticPress(onRefresh != null)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = onRefresh != null) { onRefresh?.invoke() }
            .semanticsLabel(tr("Retry")),
    ) {
        ShapeBadge(MaterialShapes.Clover4Leaf, SurfaceLevel.Inset.colorOf(scheme), size.dp, rotation = (turn.value * 2 * PI).toFloat()) {
            if (!country.isNullOrEmpty()) {
                Text(country.uppercase(), maxLines = 1, style = AppTextStyle.Footnote.of(true).copy(color = color, letterSpacing = (.04f * AppTextStyle.Footnote.size).sp))
            } else {
                GlyphIcon(AppGlyphs.proxies, size = (size * .42f).dp, color = color)
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun EgressDetails(model: StatusModel) {
    val info: Json = model.egress ?: emptyMap()
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val revision = model.detailsRevision
    BoxPage(
        "Outbound IP",
        actions = listOf(IconAction(AppGlyphs.copy, tr("Copy"), { (info["ip"] as? String)?.let { copyText(context, it) } })),
    ) {
        val rowsMap = linkedMapOf<String, Any?>(
            "IP" to info["ip"], "Country" to info["country"], "Organization" to info["organization"],
            "ASN" to info["asn"], "Type" to tr(info["type"] as? String ?: "Unknown type"),
        )
        (info["quality"] as? String)?.let { rowsMap["Quality"] = tr(it) }
        rowsMap["IP query source"] = info["ipSource"]
        rowsMap["Quality query source"] = info["source"]
        rowsMap["Last checked"] = localDate(info["qualityCheckedAt"] ?: info["checkedAt"])
        val entries = rowsMap.entries.toList()
        Section(count = entries.size) { index, position -> DetailRow(entries[index].key, entries[index].value, position) }
        if (info["qualityError"] != null) {
            val labels = mapOf("noType" to "No type data", "timeout" to "Timeout", "rateLimited" to "Rate limited", "failed" to "Unavailable", "ipMismatch" to "IP mismatch")
            val failures = rows(info["qualityFailures"])
            Section(title = tr("IP quality unavailable"), count = failures.size) { index, position ->
                val failure = failures[index]
                ListItem(
                    title = { Text(failure["source"].toString()) },
                    trailing = { Text(tr(labels[failure["status"]] ?: "Unavailable")) },
                    position = position,
                )
            }
        }
    }
}

@Composable
fun DetailRow(key: String, value: Any?, position: io.nekohasekai.sfa.compose.ui.ItemPosition) {
    ListItem(title = { Text(tr(key)) }, subtitle = { Text(value?.toString() ?: "—") }, position = position)
}

/** widgets.dart's localDate: libbox millisecond timestamps, local time, no fractions. */
fun localDate(value: Any?): String {
    val ms = value.asLong() ?: 0L
    if (ms <= 0) return "—"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(ms))
}
