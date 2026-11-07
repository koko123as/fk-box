package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.common.IpProbeCancelled
import io.nekohasekai.sfa.compose.common.RoutedIpProbe
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.ChipTone
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.CommonSwitch
import io.nekohasekai.sfa.compose.ui.DashboardChip
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.ReorderableColumn
import io.nekohasekai.sfa.compose.ui.SkeletonText
import io.nekohasekai.sfa.compose.ui.SortHandle
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * widgets/service_status.dart and common/service_probe.dart (FlClash
 * be84736, GPL-3.0): which services the current route reaches, checked by
 * libbox's CheckServices (ui_service.go, a port of FlClash's Go checks).
 * FK-box does not learn which node a check left by, so the sheet's rows show
 * the reported region rather than the node's outbound IP.
 */

/** [id] matches the checker name in libbox/ui_service.go. */
enum class ServiceTarget(val id: String, val label: String, val icon: String) {
    Google("google", "Google", "google"),
    GitHub("github", "GitHub", "github"),
    YouTube("youtube", "YouTube", "youtube"),
    ChatGpt("chatgpt", "ChatGPT", "openai"),
    Claude("claude", "Claude", "claude"),
    Gemini("gemini", "Gemini", "gemini"),
    Netflix("netflix", "Netflix", "netflix"),
    DisneyPlus("disney-plus", "Disney+", "disneyplus"),
    PrimeVideo("prime-video", "Prime Video", "primevideo"),
    Spotify("spotify", "Spotify", "spotify"),
    TikTok("tiktok", "TikTok", "tiktok"),
    Discord("discord", "Discord", "discord"),
    Bilibili("bilibili", "Bilibili", "bilibili"),
    ;

    companion object {
        fun byId(id: String): ServiceTarget? = entries.firstOrNull { it.id == id }
    }
}

enum class ServiceProbeStatus(val id: String, val label: String) {
    Available("available", "Available"),
    Unavailable("unavailable", "Unavailable"),
    Restricted("restricted", "Access restricted"),
    DisallowedIsp("disallowed-isp", "Disallowed ISP"),
    Blocked("blocked", "Blocked"),
    UnsupportedRegion("unsupported-region", "Region not supported"),
    OriginalsOnly("originals-only", "Originals only"),
    ComingSoon("coming-soon", "Coming soon"),
    Timeout("timeout", "Timeout"),
    Failed("failed", "Check failed"),
    ;

    companion object {
        fun byId(id: String?): ServiceProbeStatus = entries.firstOrNull { it.id == id } ?: Failed
    }
}

data class ServiceCheck(val status: ServiceProbeStatus, val delay: Long?, val region: String?, val checkedAt: Long)

enum class ProbePhase { Idle, Probing, Done, Failed }

data class ServiceEntry(val phase: ProbePhase = ProbePhase.Idle, val check: ServiceCheck? = null)

/** orderServiceTargets: the saved order first, the services it lacks after. */
fun orderServiceTargets(order: List<String>): List<ServiceTarget> =
    LinkedHashSet(order.mapNotNull(ServiceTarget::byId) + ServiceTarget.entries).toList()

/**
 * serviceStatusProvider: results per service for the route they were checked
 * on. A new route (start, stop, profile, mode or node change) drops them, and
 * the card checks what it shows again.
 */
@Stable
class ServiceStatusModel(private val box: BoxController) {
    val entries = mutableStateMapOf<ServiceTarget, ServiceEntry>()

    /** shownServiceCountProvider: how many services the dashboard card shows, for the manage page. */
    var shownCount by mutableStateOf(2)
    private var stamp: String? = null
    private var generation = 0

    fun entryOf(target: ServiceTarget): ServiceEntry = entries[target] ?: ServiceEntry()

    fun isLoading(target: ServiceTarget): Boolean = entryOf(target).phase == ProbePhase.Probing

    val targets: List<ServiceTarget>
        get() = orderServiceTargets((box.preferences["serviceOrder"] as? List<*>).orEmpty().filterIsInstance<String>())

    val enabled: List<ServiceTarget>
        get() {
            val disabled = (box.preferences["disabledServices"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
            return targets.filter { it.id !in disabled }.ifEmpty { listOf(targets.first()) }
        }

    /** The route the results belong to; checks wait while the service changes or a remote session is shown. */
    fun routeStamp(): String? {
        if (box.remote || box.transitioning) return null
        val selections = box.groups.map { "${it["tag"]}:${it["selected"]}" }.sorted()
        return if (box.running) {
            "run:${box.dashboard["startedAt"]}:${box.selectedProfileId}:${box.dashboard["mode"]?.toString()?.lowercase()}:${selections.joinToString(",")}"
        } else {
            "direct:${box.lan["stamp"]}"
        }
    }

    fun syncRoute(next: String?) {
        if (next == stamp) return
        stamp = next
        generation++
        entries.clear()
    }

    /** Checks each target with no result for the current route yet. */
    fun ensure(targets: List<ServiceTarget>) {
        if (stamp == null) return
        refresh(targets.filter { entryOf(it).phase == ProbePhase.Idle })
    }

    fun refresh(targets: List<ServiceTarget>) {
        val wanted = targets.filter { !isLoading(it) }
        if (wanted.isEmpty() || stamp == null) return
        val current = generation
        for (target in wanted) entries[target] = entryOf(target).copy(phase = ProbePhase.Probing)
        box.launch {
            val probe = RoutedIpProbe(box)
            try {
                probe.open()
                val items = probe.services(wanted.map { it.id }, 10_000)
                if (current != generation) return@launch
                val byName = items.associateBy { it["name"] as? String }
                for (target in wanted) {
                    val item = byName[target.id]
                    entries[target] = if (item == null) {
                        ServiceEntry(ProbePhase.Failed, null)
                    } else {
                        ServiceEntry(ProbePhase.Done, checkOf(item))
                    }
                }
            } catch (_: IpProbeCancelled) {
                if (current == generation) for (target in wanted) entries[target] = ServiceEntry()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current == generation) for (target in wanted) entries[target] = ServiceEntry(ProbePhase.Failed, null)
            } finally {
                probe.close()
            }
        }
    }

    private fun checkOf(item: Json): ServiceCheck = ServiceCheck(
        status = ServiceProbeStatus.byId(item["status"] as? String),
        delay = item["delay"].asLong()?.takeIf { it > 0 },
        region = (item["region"] as? String)?.takeIf { it.isNotEmpty() },
        checkedAt = item["checkedAt"].asLong() ?: 0,
    )
}

@Composable
private fun statusColor(status: ServiceProbeStatus): Color {
    val scheme = BoxTheme.scheme
    return when (status) {
        ServiceProbeStatus.Available -> scheme.success
        ServiceProbeStatus.Restricted, ServiceProbeStatus.DisallowedIsp, ServiceProbeStatus.Blocked,
        ServiceProbeStatus.UnsupportedRegion, ServiceProbeStatus.OriginalsOnly, ServiceProbeStatus.ComingSoon,
        -> scheme.warning
        ServiceProbeStatus.Unavailable, ServiceProbeStatus.Timeout, ServiceProbeStatus.Failed -> scheme.error
    }
}

/** _ServiceView: what the card shows for one service. */
private class ServiceView(val target: ServiceTarget, entry: ServiceEntry) {
    val phase = entry.phase
    val check = entry.check
    val loading: Boolean get() = phase == ProbePhase.Probing && check == null
    val abnormal: Boolean get() = check?.let { it.status != ServiceProbeStatus.Available } ?: (phase == ProbePhase.Failed)

    @Composable
    fun status(): Pair<String, Color> {
        val scheme = BoxTheme.scheme
        val check = check
        return when {
            check != null && check.status == ServiceProbeStatus.Available ->
                (if (check.delay != null) "${check.delay} ms" else tr(ServiceProbeStatus.Available.label)) to scheme.onSurfaceVariant
            check != null -> tr(check.status.label) to statusColor(check.status)
            phase == ProbePhase.Probing -> tr("Loading…") to scheme.onSurfaceVariant
            phase == ProbePhase.Failed -> tr(ServiceProbeStatus.Failed.label) to scheme.error
            else -> tr("Not checked") to scheme.onSurfaceVariant
        }
    }

    @Composable
    fun dot(): Color? = when {
        check != null -> statusColor(check.status)
        phase == ProbePhase.Failed -> BoxTheme.scheme.error
        else -> null
    }
}

/** _ServiceBadge: the dot sits in a notch cleared from the tile rather than on a ring. */
@Composable
private fun ServiceBadge(target: ServiceTarget, size: Float, radius: Float, color: Color, ink: Color, dot: Color?) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val px = this.size.width
            val r = radius.dp.toPx()
            val tile = Path().apply { addRoundRect(RoundRect(0f, 0f, px, px, CornerRadius(r, r))) }
            if (dot == null) {
                drawPath(tile, color)
            } else {
                val dotRadius = max(px / 10, 4.dp.toPx())
                val ring = max(px / 16, 2.dp.toPx())
                val center = Offset(px - dotRadius, px - dotRadius)
                val gap = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, dotRadius + ring)) }
                drawPath(Path.combine(PathOperation.Difference, tile, gap), color)
                drawCircle(dot, dotRadius, center)
            }
        }
        serviceIcon(target.icon)?.let { icon ->
            Image(icon, null, Modifier.size((size * .56f).dp), colorFilter = ColorFilter.tint(ink))
        }
    }
}

@Composable
private fun ServiceCell(service: ServiceView, height: Float, tile: Boolean) {
    val inset = 7f
    val (label, color) = service.status()
    val style = AppTextStyle.Caption1.of(true).tabular.copy(color = color)
    val badge: @Composable () -> Unit = {
        ServiceBadge(service.target, height - inset * 2, DashboardCorner.TAB - inset, DashboardColors.card, BoxTheme.scheme.onSurfaceVariant, service.dot())
    }
    val text: @Composable () -> Unit = {
        if (service.loading) SkeletonText(44.dp, style) else Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = style)
    }
    if (tile) {
        DashboardBlock(DashboardCorner.TAB, Modifier.fillMaxSize(), padding = PaddingValues(inset.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                badge()
                Box(Modifier.padding(start = 5.dp, end = 5.dp, bottom = 3.dp)) { text() }
            }
        }
    } else {
        DashboardBlock(DashboardCorner.TAB, Modifier.fillMaxWidth().height(height.dp), padding = PaddingValues(start = inset.dp, end = 12.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                badge()
                Box(Modifier.weight(1f)) { text() }
            }
        }
    }
}

@Composable
private fun ServiceGrid(services: List<ServiceView>, columns: Int, fill: Boolean, cellHeight: Float, gap: Float) {
    if (fill && services.size <= columns) {
        Row(Modifier.fillMaxWidth().height((cellHeight * 2 + gap).dp), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
            for (service in services) Box(Modifier.weight(1f).fillMaxHeight()) { ServiceCell(service, cellHeight, tile = true) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        for (start in services.indices step columns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                val row = services.drop(start).take(columns)
                for (service in row) Box(Modifier.weight(1f)) { ServiceCell(service, cellHeight, tile = false) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Keeps the card's checks bound to the current route. */
@Composable
private fun SyncServiceRoute(model: ServiceStatusModel) {
    val stamp = model.routeStamp()
    LaunchedEffect(stamp) { model.syncRoute(stamp) }
}

@Composable
fun ServiceStatusCard(box: BoxController, columns: Int = 2, modifier: Modifier = Modifier) {
    val model = box.serviceStatus
    val navigator = LocalBoxNavigator.current
    val density = dashboardDensity
    val mobile = !density.isDesktop
    val scale = dashboardScale
    // At most four: what the core checks at once.
    val shownCount = (if (mobile) 1 else 2) * columns
    val stacked = columns == 1
    val shown = model.enabled.take(shownCount)
    LaunchedEffect(shownCount) { model.shownCount = shownCount }
    SyncServiceRoute(model)
    val active = LocalPageActive.current
    val stamp = model.routeStamp()
    LaunchedEffect(stamp, shown, active) { if (active) model.ensure(shown) }
    val services = shown.map { ServiceView(it, model.entryOf(it)) }
    val problems = services.count { it.abnormal }
    val openSheet = { navigator?.openForm { ServiceStatusSheet(box) }; Unit }
    val title = tr("Service status")
    DashboardCard(modifier, onTap = if (mobile) openSheet else null, semanticLabel = title) {
        Column(if (mobile) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            DashboardCardHeader(
                title = title,
                glyph = AppGlyphs.verified,
                chevron = mobile,
                trailing = {
                    if (!stacked) {
                        if (problems > 0) {
                            DashboardChip(String.format(tr("%d issues"), problems), tone = ChipTone.Warn)
                        } else if (services.all { it.check != null }) {
                            DashboardChip(tr("All available"), tone = ChipTone.Ok)
                        }
                    }
                    if (!mobile) {
                        Row(Modifier.headerNudge(), verticalAlignment = Alignment.CenterVertically) {
                            if (!stacked) {
                                if (shown.any(model::isLoading)) {
                                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                                } else {
                                    DashboardIconButton(AppGlyphs.bolt, tr("Check all"), { model.refresh(shown) })
                                }
                            }
                            DashboardIconButton(AppGlyphs.chevronForward, title, openSheet)
                        }
                    }
                },
            )
            if (mobile) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.weight(1f))
            ServiceGrid(
                services = services,
                columns = columns,
                fill = !mobile,
                cellHeight = (if (mobile) 44f else if (density.isCompact) 40f else 46f) * scale,
                gap = if (density.isCompact) 6f else 8f,
            )
        }
    }
}

private fun flagOf(region: String): String =
    if (region.length == 2 && region.all { it in 'A'..'Z' }) String(Character.toChars(0x1F1E6 + (region[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (region[1] - 'A'))) else ""

/** ServiceStatusSheet: every enabled service, read from the results and checked only on request. */
@Composable
fun ServiceStatusSheet(box: BoxController) {
    val model = box.serviceStatus
    val navigator = LocalBoxNavigator.current
    SyncServiceRoute(model)
    val targets = model.enabled
    val scheme = BoxTheme.scheme
    val secondary = AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant)
    CommonScaffold(
        title = tr("Service status"),
        iconActions = listOf(
            IconAction(AppGlyphs.bolt, tr("Check all"), { model.refresh(targets) }, loading = targets.any(model::isLoading)),
            IconAction(AppGlyphs.sliders, tr("Manage services"), { navigator?.push { ServiceManagePage(box) } }),
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 16.dp + bottomInset())),
        ) {
            targets.forEachIndexed { index, target ->
                val entry = model.entryOf(target)
                val service = ServiceView(target, entry)
                val loading = entry.phase == ProbePhase.Probing
                val (label, color) = service.status()
                ListItem(
                    leading = { ServiceBadge(target, 40f, AppCorner.XL - 12f, io.nekohasekai.sfa.compose.theme.SurfaceLevel.Inset.colorOf(scheme), scheme.onSurfaceVariant, if (loading) scheme.onSurfaceVariant else service.dot()) },
                    title = { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = {
                        val region = entry.check?.region
                        when {
                            loading -> SkeletonText(96.dp, secondary)
                            region != null -> Text("${flagOf(region)} $region", maxLines = 1, style = secondary)
                            else -> Text("—", maxLines = 1, style = secondary)
                        }
                    },
                    trailing = {
                        val style = AppTextStyle.Footnote.of(true).tabular.copy(color = color)
                        if (loading) {
                            SkeletonText(44.dp, style)
                        } else {
                            Text(label, Modifier.widthIn(max = 160.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, style = style)
                        }
                    },
                    position = ItemPosition.of(index, targets.size),
                    minVerticalPadding = 12.dp,
                    startPadding = 12.dp,
                    onClick = if (loading) null else ({ model.refresh(listOf(target)) }),
                )
            }
        }
    }
}

/** ServiceManageView: turn services on or off and reorder them; at least one stays on. */
@Composable
fun ServiceManagePage(box: BoxController) {
    val model = box.serviceStatus
    val scope = rememberCoroutineScope()
    val order = remember { mutableStateListOf<ServiceTarget>().apply { addAll(model.targets) } }
    var disabled by remember { mutableStateOf((box.preferences["disabledServices"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()) }
    val enabled = order.filter { it.id !in disabled }
    val shown = enabled.take(model.shownCount).toSet()
    fun save() {
        scope.launch {
            box.setPreferences(mapOf("serviceOrder" to order.map { it.id }, "disabledServices" to disabled.toList()))
        }
    }
    CommonScaffold(title = tr("Manage services")) {
        ReorderableColumn(
            items = order.toList(),
            key = { it.id },
            onMove = { from, to ->
                order.add(to, order.removeAt(from))
                save()
            },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 16.dp + bottomInset()),
        ) { index, target ->
            val on = target.id !in disabled
            val locked = on && enabled.size == 1
            val toggle: ((Boolean) -> Unit)? = if (locked) null else ({ value ->
                disabled = if (value) disabled - target.id else disabled + target.id
                save()
            })
            val scheme = BoxTheme.scheme
            ListItem(
                leading = { ServiceBadge(target, 40f, AppCorner.XL - 12f, io.nekohasekai.sfa.compose.theme.SurfaceLevel.Inset.colorOf(scheme), if (on) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = .38f), null) },
                title = { Text(target.label, style = if (on) AppTextStyle.Body.of().copy(color = scheme.onSurface) else AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant)) },
                subtitle = if (on && target in shown) ({ Text(tr("Shown on the dashboard")) }) else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommonSwitch(on, toggle)
                        SortHandle()
                    }
                },
                position = ItemPosition.of(index, order.size),
                minVerticalPadding = 12.dp,
                startPadding = 12.dp,
                onClick = toggle?.let { { it(!on) } },
            )
        }
    }
}
