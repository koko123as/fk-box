package io.nekohasekai.sfa.compose.pages

import io.nekohasekai.sfa.compose.ui.IconBadge


import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.BoxData
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.alphaBlend
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.BoxPanel
import io.nekohasekai.sfa.compose.ui.ChipTone
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.CommonSwitch
import io.nekohasekai.sfa.compose.ui.DashboardChip
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.AppIconButton
import io.nekohasekai.sfa.compose.ui.AppIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListStyles
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.PanelTitle
import io.nekohasekai.sfa.compose.ui.SWITCH_TRACK_HEIGHT
import io.nekohasekai.sfa.compose.ui.SWITCH_TRACK_WIDTH
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.SkeletonText
import io.nekohasekai.sfa.compose.ui.TextPillButton
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.isChinese
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * pages/dashboard.dart and the dashboard panels in components/ (FlClash
 * v0.9.2 adaptations, GPL-3.0), over the native state BoxController carries.
 */

val dashboardCards = listOf("Status", "Network speed", "Outbound mode", "Current profile", "Proxy groups", "Memory", "Intranet IP", "System HTTP proxy")
private val defaultDashboardCards = listOf("Status", "Network speed", "Outbound mode", "Current profile", "Proxy groups")
private val defaultHiddenCards = listOf("Memory", "Intranet IP")

fun dashboardOrder(prefs: Json): List<String> {
    val saved = ((prefs["dashboardCards"] as? List<*>) ?: defaultDashboardCards).filterIsInstance<String>().filter { it in dashboardCards }
    return LinkedHashSet(saved + dashboardCards).toList()
}

@Composable
fun DashboardPage(box: BoxController) {
    val available = buildMap<String, @Composable () -> Unit> {
        put("Status") { StatusPanel(box) }
        put("Intranet IP") { IntranetPanel(box) }
        put("Network speed") { SpeedPanel(box) }
        if ((box.dashboard["modes"] as? List<*>).orEmpty().isNotEmpty()) put("Outbound mode") { ModePanel(box) }
        put("Current profile") { ProfilePanel(box) }
        put("Proxy groups") { ProxyGroupsPanel(box) }
        put("Memory") { MemoryPanel(box) }
        if (box.dashboard["systemProxyAvailable"] == true) put("System HTTP proxy") { SystemProxyPanel(box) }
    }
    val hidden = (box.preferences["hiddenCards"] as? List<*>) ?: defaultHiddenCards
    val shown = dashboardOrder(box.preferences).filter { available.containsKey(it) && (it == "Status" || it !in hidden) }
    var editor by remember { mutableStateOf<DashboardEditorState?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // pages/dashboard.dart's _saveLayout: cards this device cannot show keep their place.
    suspend fun saveLayout(next: List<String>): Boolean {
        val availableKeys = available.keys
        val order = dashboardOrder(box.preferences)
        val result = next.toMutableList()
        val hiddenSet = ((box.preferences["hiddenCards"] as? List<*>) ?: defaultHiddenCards)
            .filterIsInstance<String>().filter { it in dashboardCards }.toMutableSet()
        var at = 0
        for (card in order) {
            if (card in availableKeys) {
                val index = result.indexOf(card)
                if (index >= 0) at = index + 1
            } else {
                result.add(at++, card)
            }
        }
        for (card in order) if (card !in result) result.add(card)
        for (card in availableKeys) if (card in next) hiddenSet.remove(card) else hiddenSet.add(card)
        hiddenSet.remove("Status")
        return box.setPreferences(mapOf("dashboardCards" to result, "hiddenCards" to hiddenSet.toList()))
    }
    // dashboard.dart: the desktop grid has no editing; the content size is known a frame late.
    var desktopLayout by remember { mutableStateOf(false) }
    if (desktopLayout && editor != null) editor = null
    val editing = editor
    CommonScaffold(
        title = tr(if (editing != null) "Edit dashboard" else "Dashboard"),
        backgroundColor = SurfaceLevel.Page.colorOf(BoxTheme.scheme),
        loading = saving,
        confirmationAction = editing?.let { state ->
            IconAction(AppIcons.check, tr("Done"), if (saving) null else ({ scope.launch { state.finish() } }))
        },
        iconActions = if (editing == null && !desktopLayout) listOf(IconAction(AppIcons.edit, tr("Dashboard cards"), { editor = DashboardEditorState(shown) })) else emptyList(),
    ) {
        val pageScroll = rememberScrollState()
        var viewportBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { viewportBounds = it.boundsInRoot() }) {
            // layout.dart's DashboardDensity, read from the content room over the text scale.
            val scale = max(1f, LocalDensity.current.fontScale)
            val density = io.nekohasekai.sfa.compose.ui.dashboardDensityOf(maxWidth.value / scale, maxHeight.value / scale)
            if (density.isDesktop != desktopLayout) androidx.compose.runtime.SideEffect { desktopLayout = density.isDesktop }
            // dashboardMobileSideOf: the phone's one centred column.
            val side = max(16f, (maxWidth.value - io.nekohasekai.sfa.compose.ui.DASHBOARD_MOBILE_MAX_WIDTH) / 2).dp
            val gap = io.nekohasekai.sfa.compose.ui.cardSpacingOf(io.nekohasekai.sfa.compose.ui.LocalViewMode.current)
            val banners: @Composable () -> Unit = { DashboardBanners(box) }
            if (density.isDesktop && editing == null) {
                DashboardGrid(
                    box = box,
                    density = density,
                    scale = scale,
                    columns = io.nekohasekai.sfa.compose.ui.dashboardColumnsOf(maxWidth.value / scale),
                    gap = gap,
                    top = contentTopPadding(),
                    bottom = bottomInset(),
                    banners = banners,
                    hasBanners = box.remote || box.applyRequired != null || rows(box.dashboard["deprecated"]).isNotEmpty(),
                )
                return@BoxWithConstraints
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScroll)
                    .padding(PaddingValues(start = side, end = side, top = contentTopPadding(), bottom = 16.dp + bottomInset())),
                verticalArrangement = Arrangement.spacedBy(if (editing != null) 0.dp else gap),
            ) {
              if (editing != null) {
                DashboardEditor(
                    state = editing,
                    shown = shown,
                    available = dashboardCards.filter { available.containsKey(it) },
                    cardContent = { available[it] },
                    gap = gap,
                    onChanged = { saveLayout(it) },
                    onBusyChanged = { saving = it },
                    onDone = { editor = null },
                    scrollState = pageScroll,
                    viewport = { viewportBounds },
                )
              } else {
                if (box.remote || box.applyRequired != null) DashboardBanners(box, deprecated = false)
                for (card in shown) available[card]?.invoke()
                DashboardBanners(box, remoteAndApply = false)
              }
            }
        }
    }
}

/** The remote, restart and deprecation rows above (and below) the cards. */
@Composable
private fun DashboardBanners(box: BoxController, remoteAndApply: Boolean = true, deprecated: Boolean = true) {
    if (remoteAndApply) {
        if (box.remote) {
            ListItem(
                title = { Text(box.dashboard["remote"].toString()) },
                subtitle = { Text(tr("Remote control")) },
                trailing = { TextPillButton(tr("Disconnect"), { box.launchAct { disconnectRemoteServer() } }) },
            )
        }
        if (box.applyRequired != null) {
            ListItem(
                title = { Text(tr("Restart required")) },
                trailing = {
                    TextPillButton(tr("Apply"), {
                        box.launchAct(then = { if (it) box.clearApplyRequired() }) { reloadService(restart = true) }
                    })
                },
            )
        }
    }
    if (deprecated) {
        for (note in rows(box.dashboard["deprecated"])) {
            ListItem(
                title = { Text(note["message"].toString()) },
                onClick = note["url"]?.let { url -> { box.launchAct { openUrl(url as? String) } } },
            )
        }
    }
}

// Adapted from FlClash v0.9.2 dashboard/widgets/traffic.dart.
@Composable
internal fun SpeedPanel(box: BoxController) {
    BoxPanel {
        Column {
            PanelTitle("Network speed", AppIcons.speed)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (upload in listOf(true, false)) Box(Modifier.weight(1f)) { SpeedTile(box, upload) }
            }
        }
    }
}

@Composable
private fun SpeedTile(box: BoxController, upload: Boolean) {
    val scheme = BoxTheme.scheme
    val color = if (upload) scheme.onSurfaceVariant else scheme.primary
    val rates = whileActive { box.rates }
    val history = activeReader { if (upload) box.uploadHistory else box.downloadHistory }
    val show = bytes(rates[if (upload) "upload" else "download"] as? Number ?: 0).split(" ")
    val scale = max(1f, LocalDensity.current.fontScale)
    val textHeight = 14 + 4 + (AppTextStyle.Footnote.leading + AppTextStyle.Title1.leading + AppTextStyle.Caption1.leading) * scale
    val shape = AppShape.card
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(SurfaceLevel.Block.colorOf(scheme), shape)) {
        val chartHeight = max(36f, maxWidth.value / 1.06f - textHeight - 12 + 2)
        Box(Modifier.fillMaxWidth().height((chartHeight - 2 + 12 + textHeight).dp)) {
            io.nekohasekai.sfa.compose.ui.LineChart(
                values = history,
                capacity = 30,
                minScale = 8.0 * 1024,
                color = if (upload) alphaBlend(scheme.primary.copy(alpha = .45f), SurfaceLevel.Card.colorOf(scheme)) else scheme.primary,
                baselineRatio = 1f,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(chartHeight.dp),
            )
            Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = (chartHeight - 2 + 12).dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (upload) AppIcons.arrowUp else AppIcons.arrowDown, size = 16.dp, color = color)
                    Text(tr(if (upload) "Upload" else "Download"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Footnote.of(true).copy(color = scheme.onSurfaceVariant))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(AppTextStyle.Title1.of(true).tabular.copy(letterSpacing = (-.02f * AppTextStyle.Title1.size).sp, color = scheme.onSurface).toSpanStyle()) { append(show.first()) }
                        withStyle(AppTextStyle.Footnote.of(true).copy(color = scheme.onSurfaceVariant).toSpanStyle()) { append(" ${show.last()}/s") }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = AppTextStyle.Title1.of(true),
                )
                Text(
                    "${tr("Total")} ${rates[if (upload) "uploadTotal" else "downloadTotal"] ?: "0 B"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTextStyle.Caption1.of().tabular.copy(color = scheme.onSurfaceVariant),
                )
            }
        }
    }
}

// FlClash Android's outbound_mode.dart, using sing-box's case-insensitive modes.
@Composable
private fun ModePanel(box: BoxController) {
    val scheme = BoxTheme.scheme
    val modes = (box.dashboard["modes"] as? List<*>).orEmpty().filterIsInstance<String>()
    val selected = (box.dashboard["mode"] as? String ?: "").lowercase()
    val labels = mapOf("rule" to "Rule", "global" to "Global", "direct" to "Direct")
    val icons = mapOf("rule" to AppIcons.rules, "global" to AppIcons.proxies, "direct" to AppIcons.target)
    BoxPanel {
        Column {
            PanelTitle("Outbound mode", AppIcons.split)
            Spacer(Modifier.height(12.dp))
            ConnectedGroup {
                modes.forEachIndexed { index, mode ->
                    val key = mode.lowercase()
                    val label = tr(labels[key] ?: mode)
                    ConnectedButton(
                        selected = key == selected,
                        background = SurfaceLevel.Block.colorOf(scheme),
                        label = label,
                        onTap = { if (key != selected) box.launchAct { setMode(mode) } },
                        modifier = Modifier.weight(1f),
                    ) { foreground ->
                        Column(Modifier.fillMaxWidth().height(52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            AppIcon(icons[key] ?: AppIcons.split, size = 18.dp, color = foreground)
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Footnote.of(true).copy(color = foreground))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// components/profile_panel.dart
// ---------------------------------------------------------------------------

@Composable
internal fun ProfilePanel(box: BoxController) {
    val scheme = BoxTheme.scheme
    val navigator = LocalBoxNavigator.current
    val profile = if (box.remote) null else box.selectedProfile
    val name = if (box.remote) tr("Remote configuration") else profile?.get("name")?.toString()
    val label = AppTextStyle.Caption1.of().copy(color = scheme.onSurfaceVariant)
    var updating by remember { mutableStateOf(false) }
    // dashboard/widgets/profiles.dart: the grid's fixed height puts the usage at the bottom.
    val density = io.nekohasekai.sfa.compose.ui.LocalDashboardDensity.current
    BoxPanel(onClick = {
        navigator?.openForm { if (box.remote) ProfileDetailsPage(box, null) else if (profile == null) AddProfilePage(box) else ProfileDetailsPage(box, profile) }
    }) {
        Column(Modifier.fillMaxWidth().then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
            PanelTitle("Profile", AppIcons.profiles, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile?.get("remote") == true) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            if (updating) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                AppIconButton(AppIcons.sync, tr("Update"), {
                                    updating = true
                                    box.launchAct(then = { updating = false }) { updateProfile(profile?.get("id").asLong()) }
                                }, size = 26.dp, iconSize = 18.dp)
                            }
                        }
                    }
                    AppIcon(AppIcons.chevronForward, size = 18.dp)
                }
            })
            Spacer(Modifier.height(if (density.isDesktop) 10.dp else 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name ?: tr("Add profile"), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = (if (density.isCompact) AppTextStyle.Callout else AppTextStyle.Headline).of(true).copy(color = scheme.onSurface))
                if (profile != null) {
                    Spacer(Modifier.width(8.dp))
                    ProfileCoreChip(profile)
                }
                if (!box.remote && box.profiles.size > 1) {
                    AppIconButton(AppIcons.swap, tr("Switch profile"), if (box.transitioning) null else ({ navigator?.openForm { ProfilesPage(box, picker = true) } }), size = 32.dp, iconSize = 18.dp)
                }
            }
            if (profile != null || box.remote) {
                // dashboard/widgets/profiles.dart: days left replaces the source once the provider reports an expiry,
                // and the usage meter replaces the counts once it reports a quota.
                val info = profile?.subscriptionInfo()
                val days = subscriptionDaysLabel(info)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (profile != null) ProfileUpdateTime(profile["updatedAt"].asLong(), label, showLabel = true)
                    }
                    if (days != null) {
                        Text(days.first, maxLines = 1, style = days.second?.let { label.copy(color = it) } ?: label)
                    } else {
                        Text(tr(if (box.remote || profile?.get("remote") == true) "Remote" else "Local"), style = label)
                    }
                }
                if (density.isDesktop) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(14.dp))
                if (info?.usage != null) SubscriptionUsageMeter(info) else ProfileStats(box, profile)
            }
        }
    }
}

/** components/profile_card.dart's profileUpdateLabel. */
fun profileUpdateLabel(milliseconds: Long?, now: Long, chinese: Boolean): String {
    if (milliseconds == null || milliseconds <= 0) return if (chinese) "未知" else "Unknown"
    val elapsed = now - milliseconds
    val days = elapsed / 86_400_000
    for ((count, en, zh) in listOf(
        Triple(days / 365, "y", "年"), Triple(days / 30, "mo", "个月"), Triple(days, "d", "天"),
        Triple(elapsed / 3_600_000, "h", "小时"), Triple(elapsed / 60_000, "m", "分钟"),
    )) {
        if (count > 0) return if (chinese) "$count ${zh}前" else "$count$en ago"
    }
    return if (chinese) "刚刚" else "Just now"
}

@Composable
internal fun ProfileUpdateTime(milliseconds: Long?, style: androidx.compose.ui.text.TextStyle, showLabel: Boolean) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val age = profileUpdateLabel(milliseconds, now, isChinese())
    Text(if (showLabel) "${tr("Last updated")} $age" else age, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
}

@Composable
internal fun ProfileStats(box: BoxController, profile: Json?) {
    val scheme = BoxTheme.scheme
    var stats by remember(profile?.get("id"), box.profilesRevision) { mutableStateOf<Json?>(null) }
    var waiting by remember(profile?.get("id"), box.profilesRevision) { mutableStateOf(profile != null) }
    LaunchedEffect(profile?.get("id"), box.profilesRevision) {
        val id = profile?.get("id").asLong() ?: return@LaunchedEffect
        stats = runCatching { box.profileStats(id).await() }.getOrNull()
        waiting = false
    }
    val figure = AppTextStyle.Title3.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface)
    val label = AppTextStyle.Caption1.of().copy(color = scheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((key, name) in listOf("groups" to "Proxy groups", "outbounds" to "Outbounds", "rules" to "Routing rules")) {
            Column {
                if (waiting) SkeletonText(34.dp, figure) else Text(stats?.get(key)?.toString() ?: "—", maxLines = 1, style = figure)
                Text(tr(name), maxLines = 1, style = label)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// components/proxy_groups_panel.dart
// ---------------------------------------------------------------------------

@Composable
private fun ProxyGroupsPanel(box: BoxController) {
    val scheme = BoxTheme.scheme
    val navigator = LocalBoxNavigator.current
    val groups = box.groups
    val timeout = groups.flatMap { rows(it["items"]) }
        .filter { (it["testedAt"].asLong() ?: 0) > 0 && (it["delay"].asLong() ?: 0) <= 0 }
        .map { it["tag"].toString() }.toSet()
    BoxPanel(onClick = if (groups.isEmpty()) null else ({ navigator?.openForm { ProxiesPage(box) } })) {
        Column {
            PanelTitle("Proxy groups", AppIcons.layers, trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (timeout.isNotEmpty()) DashboardChip("${tr("Timeout")} ${timeout.size}", tone = ChipTone.Warn)
                    if (groups.isNotEmpty()) AppIcon(AppIcons.chevronForward, size = 18.dp)
                }
            })
            Spacer(Modifier.height(12.dp))
            if (groups.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconBadge(AppIcons.layers, 56.dp, SurfaceLevel.Block.colorOf(scheme), scheme.onSurfaceVariant)
                    Text(
                        tr(if (box.running) "No proxy groups in this configuration" else "Start the service to view proxy groups"),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(AppTextStyle.Display.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface).toSpanStyle()) { append("${groups.size}") }
                            withStyle(AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant).toSpanStyle()) { append(" ${tr("Groups")}") }
                        },
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = AppTextStyle.Display.of(true),
                    )
                    GroupIconStack(groups)
                }
            }
        }
    }
}

@Composable
internal fun GroupIconStack(groups: List<Json>, size: Float = 40f) {
    val scheme = BoxTheme.scheme
    val shown = groups.take(4)
    val rest = groups.size - shown.size
    val count = shown.size + if (rest > 0) 1 else 0
    val tile = AppShape.card
    val ring = AppShape.card
    val step = size - 8
    Box(Modifier.width((count * size - (count - 1) * 8).dp).height(size.dp)) {
        shown.forEachIndexed { index, group ->
            // The 3 dp ring outside the tile in the card color.
            Box(Modifier.offset(x = (index * step - 3).dp, y = (-3).dp).requiredSize((size + 6).dp).background(SurfaceLevel.Card.colorOf(scheme), ring))
            Box(
                Modifier.offset(x = (index * step).dp).size(size.dp).background(SurfaceLevel.Inset.colorOf(scheme), tile).padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val tag = group["tag"].toString()
                val letters = tag.codePoints().limit(2).toArray().let { String(it, 0, it.size) }.uppercase()
                Text(letters, maxLines = 1, style = AppTextStyle.Caption1.of(true).copy(color = scheme.onSurfaceVariant))
            }
        }
        if (rest > 0) {
            Box(Modifier.offset(x = (shown.size * step - 3).dp, y = (-3).dp).requiredSize((size + 6).dp).background(SurfaceLevel.Card.colorOf(scheme), ring))
            Box(Modifier.offset(x = (shown.size * step).dp).size(size.dp).background(SurfaceLevel.Block.colorOf(scheme), tile), contentAlignment = Alignment.Center) {
                Text("+$rest", style = AppTextStyle.Caption1.of(true).tabular.copy(color = scheme.onSurfaceVariant))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// components/memory_panel.dart and data_subscription.dart
// ---------------------------------------------------------------------------

/** Shares native sampling between visible consumers of the same data. */
@Composable
fun BoxDataSubscription(box: BoxController, data: BoxData) {
    val owner = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, data) {
        val observer = LifecycleEventObserver { _, _ ->
            box.setDataVisible(data, owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        box.setDataVisible(data, owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            box.setDataVisible(data, owner, false)
        }
    }
}

@Composable
internal fun MemoryPanel(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    if (LocalPageActive.current) BoxDataSubscription(box, BoxData.Memory)
    val density = io.nekohasekai.sfa.compose.ui.LocalDashboardDensity.current
    BoxPanel(onClick = { navigator?.openForm { MemoryDetails(box) } }) {
        Column(Modifier.fillMaxWidth().then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
            PanelTitle("Memory", AppIcons.memory, trailing = { AppIcon(AppIcons.chevronForward, size = 18.dp) })
            Spacer(Modifier.height(if (density.isCompact) 4.dp else if (density.isDesktop) 6.dp else 12.dp))
            if (density.isDesktop) Box(Modifier.weight(1f)) { MemorySummary(box.memory, desktop = true) } else MemorySummary(box.memory)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MemorySummary(snapshot: Json, desktop: Boolean = false) {
    val scheme = BoxTheme.scheme
    val total = bytes(snapshot["total"] as? Number ?: 0).split(" ")
    val parts = buildList {
        if (snapshot["remote"] != true) add(Triple("App", snapshot["app"] as? Number ?: 0, scheme.primary))
        add(Triple("Core", snapshot["core"] as? Number ?: 0, alphaBlend(scheme.primary.copy(alpha = .45f), SurfaceLevel.Card.colorOf(scheme))))
    }
    val figure = if (desktop) AppTextStyle.Title2 else AppTextStyle.Title1
    Column(if (desktop) Modifier.fillMaxSize() else Modifier) {
        Text(
            buildAnnotatedString {
                withStyle(figure.of(true).tabular.copy(color = scheme.onSurface).toSpanStyle()) { append(if (snapshot.isEmpty()) "—" else total.first()) }
                if (snapshot.isNotEmpty()) withStyle(AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant).toSpanStyle()) { append(" ${total.last()}") }
            },
            maxLines = 1,
            style = figure.of(true),
        )
        if (desktop) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(14.dp))
        MemoryBar(parts)
        Spacer(Modifier.height(if (desktop && io.nekohasekai.sfa.compose.ui.LocalDashboardDensity.current.isCompact) 8.dp else 10.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, value, color) in parts) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
                    Text(tr(name), style = ListStyles.caption)
                    Text(if (snapshot.isEmpty()) "—" else bytes(value), style = AppTextStyle.Footnote.of(true).tabular.copy(color = scheme.onSurface))
                }
            }
        }
    }
}

@Composable
private fun MemoryBar(parts: List<Triple<String, Number, androidx.compose.ui.graphics.Color>>) {
    val total = parts.sumOf { max(0.0, it.second.toDouble()) }
    val positive = parts.filter { it.second.toDouble() > 0 }
    Row(Modifier.fillMaxWidth().height(8.dp).clip(AppShape.full)) {
        if (total == 0.0) {
            Box(Modifier.fillMaxSize().background(SurfaceLevel.Inset.colorOf(BoxTheme.scheme)))
        } else {
            positive.forEachIndexed { index, part ->
                if (index > 0) Spacer(Modifier.width(2.dp))
                val percent = Math.round(part.second.toDouble() * 100 / total).toFloat()
                Box(Modifier.weight(max(1f, percent * 100)).fillMaxSize().background(part.third))
            }
        }
    }
}

@Composable
private fun MemoryDetails(box: BoxController) {
    BoxDataSubscription(box, BoxData.Memory)
    val memory = box.memory
    val total = memory["core"].let { (it as? Number)?.toDouble() } ?: 0.0
    var releasing by remember { mutableStateOf(false) }
    val actions = if (memory["remote"] != true && memory["running"] == true) {
        listOf(IconAction(AppIcons.broom, tr("Release memory"), if (releasing) null else ({
            releasing = true
            box.launchAct(then = { ok ->
                if (ok) box.message("Memory released")
                releasing = false
            }) { releaseMemory() }
        })))
    } else {
        emptyList()
    }
    BoxPage("Memory", actions = actions, loading = releasing) {
        Spacer(Modifier.height(8.dp))
        Text(tr("Total"), style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurface))
        Spacer(Modifier.height(4.dp))
        MemorySummary(memory)
        Spacer(Modifier.height(12.dp))
        Text(
            tr(
                if (memory["remote"] == true) {
                    "Memory reported by the remote core. Detailed accounting is unavailable."
                } else {
                    "App and core share one process. Core runtime memory is separated from process RSS, not added twice."
                },
            ),
            style = ListStyles.caption,
        )
        if (memory["remote"] != true) {
            val appRows = listOf("Process RSS" to (memory["rss"] as? Number ?: 0), "App and shared memory" to (memory["app"] as? Number ?: 0))
            Section(title = tr("App"), count = appRows.size) { index, position ->
                ListItem(title = { Text(tr(appRows[index].first)) }, trailing = { Text(bytes(appRows[index].second)) }, position = position)
            }
        }
        val coreRows = mutableListOf<@Composable (io.nekohasekai.sfa.compose.ui.ItemPosition) -> Unit>()
        if (memory["running"] != true && memory["remote"] != true) coreRows += { p -> ListItem(title = { Text(tr("Not running")) }, position = p) }
        for ((key, label) in listOf("heapInuse" to "Heap in use", "heapIdle" to "Retained idle heap", "stack" to "Stack", "runtimeOther" to "Other runtime memory")) {
            val value = memory[key] as? Number ?: continue
            coreRows += { p ->
                ListItem(
                    title = { Text(tr(label)) },
                    subtitle = { Text(bytes(value)) },
                    trailing = { Text("${if (total == 0.0) "0.0" else String.format(java.util.Locale.ROOT, "%.1f", value.toDouble() * 100 / total)}%") },
                    position = p,
                )
            }
        }
        memory["goroutines"]?.let { g -> coreRows += { p -> ListItem(title = { Text(tr("Goroutines")) }, trailing = { Text(g.toString()) }, position = p) } }
        Section(title = tr("Core"), count = coreRows.size) { index, position -> coreRows[index](position) }
    }
}

// ---------------------------------------------------------------------------
// components/intranet_panel.dart
// ---------------------------------------------------------------------------

private fun copyText(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun lanSubtitle(box: BoxController): String {
    if (box.selectedProfileId == -1L) return tr("No profile")
    if (box.lan["error"] != null) return tr("Unavailable")
    val active = box.running && box.lan["running"] == true
    val bindings = rows(box.lan[if (active) "activeBindings" else "bindings"])
    if (bindings.isEmpty()) return tr("Add proxy listener")
    if (box.lan["enabled"] != true) return tr("Disabled")
    val ports = "${tr("Port")}: " + bindings.filter { it["lan"] == true }.map { it["port"].asLong() }.distinct()
        .map { port -> if (port == 0L) tr("Automatic port") else port.toString() }.joinToString(", ")
    if (!active) return "$ports · ${tr("Not running")}"
    if (box.lanProxyEndpoints.isEmpty()) return "$ports · ${tr("Unavailable")}"
    return ports
}

private fun changeLan(box: BoxController, navigator: io.nekohasekai.sfa.compose.ui.BoxNavigator?, enabled: Boolean) {
    if (!box.canSetLanProxy) return
    if (enabled && rows(box.lan["bindings"]).isEmpty()) {
        val revision = box.lan["revision"] as? String ?: ""
        val profileId = box.selectedProfileId
        navigator?.openForm { NewLANProxy(box, profileId, revision) }
    } else {
        box.launch { box.setLanProxy(enabled) }
    }
}

@Composable
internal fun IntranetPanel(box: BoxController) {
    if (LocalPageActive.current) BoxDataSubscription(box, BoxData.Lan)
    val scheme = BoxTheme.scheme
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    val addresses = box.lanAddresses
    val copy = box.lanCopyText
    // intranet_ip.dart: the grid uses Title2 and, when compact, a tighter LAN row.
    val density = io.nekohasekai.sfa.compose.ui.LocalDashboardDensity.current
    val figureText = if (density.isDesktop) AppTextStyle.Title2 else AppTextStyle.Title1
    val figure = figureText.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface)
    val copied = tr("Copied")
    BoxPanel {
        Column(Modifier.fillMaxWidth().then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
            PanelTitle("Intranet IP", AppIcons.devices, trailing = {
                Box(Modifier.offset(x = 8.dp)) {
                    AppIconButton(AppIcons.copy, tr("Copy"), if (copy == null) null else ({
                        box.lanCopyText?.let {
                            copyText(context, it)
                            box.message(copied)
                        }
                    }), size = 32.dp, iconSize = 18.dp, color = scheme.onSurfaceVariant)
                }
            })
            Spacer(Modifier.height(if (density.isCompact) 8.dp else if (density.isDesktop) 10.dp else 12.dp))
            Box(
                Modifier.fillMaxWidth().height((figureText.leading * LocalDensity.current.fontScale).dp)
                    .clickable { navigator?.openForm { IntranetDetails(box) } },
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    box.lan["addresses"] !is List<*> -> SkeletonText(120.dp, figure)
                    addresses.isEmpty() -> Text(tr("No network"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = figure.copy(color = scheme.onSurfaceVariant))
                    else -> androidx.compose.foundation.text.BasicText(
                        addresses.first(),
                        style = figure,
                        maxLines = 1,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = figureText.size.sp, stepSize = 0.5.sp),
                    )
                }
            }
            if (density.isDesktop) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(14.dp))
            if (box.remote) {
                Box(Modifier.fillMaxWidth().clip(AppShape.card).background(SurfaceLevel.Block.colorOf(scheme))) {
                    ListItem(
                        leading = { AppIcon(AppIcons.wifi, size = 32.dp) },
                        title = { Text(tr("This device")) },
                        subtitle = { Text(tr("Device network addresses")) },
                    )
                }
            } else {
                LanProxyRow(box.lan["enabled"] == true, lanSubtitle(box), if (box.canSetLanProxy) ({ value: Boolean -> changeLan(box, navigator, value) }) else null, compact = density.isCompact)
            }
        }
    }
}

/** The LAN proxy switch row: an icon badge, the title and subtitle, and a switch. */
@Composable
private fun LanProxyRow(value: Boolean, subtitle: String, onChanged: ((Boolean) -> Unit)?, compact: Boolean = false) {
    val scheme = BoxTheme.scheme
    val title = tr("LAN proxy")
    val shape = AppShape.card
    val icon = if (compact) 32 else 38
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(SurfaceLevel.Block.colorOf(scheme)).clickable(enabled = onChanged != null) { onChanged?.invoke(!value) }) {
        // The icon yields its room to larger text.
        val showIcon = title.length * 15 * LocalDensity.current.fontScale * .6f <= maxWidth.value - 22 - icon - 24 - SWITCH_TRACK_WIDTH
        Row(
            if (compact) Modifier.padding(start = if (showIcon) 8.dp else 12.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
            else Modifier.padding(start = if (showIcon) 10.dp else 14.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                IconBadge(AppIcons.wifi, icon.dp, if (value) scheme.primary else SurfaceLevel.Card.colorOf(scheme), if (value) scheme.onPrimary else scheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface))
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ListStyles.caption)
            }
            Box(Modifier.width(SWITCH_TRACK_WIDTH.dp).height(SWITCH_TRACK_HEIGHT.dp)) { CommonSwitch(value, onChanged) }
        }
    }
}

private fun protocol(binding: Json, tr: (String) -> String): String = when (binding["type"]) {
    "mixed" -> tr(if (binding["tls"] == true) "HTTP / SOCKS over TLS" else "HTTP / SOCKS")
    "http" -> if (binding["tls"] == true) "HTTPS" else "HTTP"
    else -> "SOCKS"
}

@Composable
private fun IntranetDetails(box: BoxController) {
    BoxDataSubscription(box, BoxData.Lan)
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    val active = !box.remote && box.running && box.lan["running"] == true
    val bindings = rows(box.lan[if (active) "activeBindings" else "bindings"])
    val strings = io.nekohasekai.sfa.compose.BoxStrings.zh
    val chinese = isChinese()
    val t: (String) -> String = { if (chinese) strings[it] ?: it else it }
    BoxPage("Intranet IP", actions = listOf(IconAction(AppIcons.refresh, tr("Reload"), if (box.lanChanging) null else ({ box.refreshLan() })))) {
        val addresses = box.lanAddresses
        Section(title = tr("This device"), count = max(1, addresses.size)) { index, position ->
            if (addresses.isEmpty()) {
                ListItem(title = { Text(tr("No network")) }, position = position)
            } else {
                val address = addresses[index]
                ListItem(
                    title = { Text(address) },
                    trailing = { AppIconButton(AppIcons.copy, tr("Copy"), { copyText(context, address); box.message(t("Copied")) }) },
                    position = position,
                )
            }
        }
        if (!box.remote) {
            Spacer(Modifier.height(24.dp))
            LanProxyRow(box.lan["enabled"] == true, lanSubtitle(box), if (box.canSetLanProxy) ({ value: Boolean -> changeLan(box, navigator, value) }) else null)
            box.lan["error"]?.let { ErrorState(it.toString()) { box.refreshLan() } }
            val listenerRows = mutableListOf<@Composable (io.nekohasekai.sfa.compose.ui.ItemPosition) -> Unit>()
            if (!active) listenerRows += { p -> ListItem(title = { Text(tr("Not running")) }, position = p) }
            for (binding in bindings) {
                val tag = (binding["tag"] as? String)?.takeIf { it.isNotEmpty() } ?: protocol(binding, t)
                val port = binding["port"].asLong()
                val subtitle = "${protocol(binding, t)} · ${if (port == 0L) t("Automatic port") else "${t("Port")}: $port"}" +
                    if (binding["authentication"] == true) " · ${t("Authentication required")}" else ""
                listenerRows += { p -> ListItem(title = { Text(tag) }, subtitle = { Text(subtitle) }, position = p) }
                if (active) {
                    for (endpoint in (binding["endpoints"] as? List<*>).orEmpty().filterIsInstance<String>()) {
                        listenerRows += { p ->
                            ListItem(
                                title = { Text(endpoint) },
                                trailing = { AppIconButton(AppIcons.copy, tr("Copy"), { copyText(context, endpoint); box.message(t("Copied")) }) },
                                position = p,
                            )
                        }
                    }
                }
            }
            if (bindings.isEmpty()) listenerRows += { p -> ListItem(title = { Text(tr("No local proxy listener")) }, position = p) }
            Section(title = tr("Local proxy listeners"), count = listenerRows.size) { index, position -> listenerRows[index](position) }
            val selected = box.selectedProfile
            if (selected != null) {
                Section(count = 1) { _, position ->
                    io.nekohasekai.sfa.compose.ui.ListItemOpen(tr("Open configuration"), position, leading = AppIcons.code) {
                        if (selected.runsSingBox()) ProfileConfigurationPage(box, selected) else ProfileEditor(box, selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewLANProxy(box: BoxController, profileId: Long, revision: String) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("2080") }
    var invalid by remember { mutableStateOf(false) }
    BoxPage(
        "Add proxy listener",
        loading = box.lanChanging,
        actions = listOf(
            IconAction(AppIcons.check, tr("Save"), if (!box.canSetLanProxy) null else ({
                val value = port.toIntOrNull()
                if (value == null || value !in 1..65535) {
                    invalid = true
                } else {
                    scope.launch {
                        if (box.setLanProxy(true, port = value, profileId = profileId, revision = revision)) navigator?.pop()
                    }
                }
            })),
        ),
    ) {
        OutlinedTextField(
            value = port,
            onValueChange = {
                port = it
                invalid = false
            },
            label = { Text(tr("Port")) },
            isError = invalid,
            supportingText = { if (invalid) Text(tr("Invalid proxy port")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = AppShape.card,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(tr("Adds an HTTP and SOCKS proxy to the selected profile."), style = ListStyles.caption)
    }
}

// ---------------------------------------------------------------------------
// components/system_proxy_panel.dart
// ---------------------------------------------------------------------------

@Composable
internal fun SystemProxyPanel(box: BoxController) {
    val scheme = BoxTheme.scheme
    val enabled = box.dashboard["systemProxy"] == true
    val title = tr("System HTTP proxy")
    val change: (Boolean) -> Unit = { box.launchAct { setSystemProxy(it) } }
    // network_takeover.dart's _tileMetricsOf: the grid's control-row tile, the icon and switch inset as from the edges.
    val density = io.nekohasekai.sfa.compose.ui.LocalDashboardDensity.current
    val tile = density.isDesktop
    val icon = if (!tile) 48.dp else if (density.isCompact) 36.dp else 40.dp
    val padding = if (!tile) PaddingValues(horizontal = 20.dp, vertical = 16.dp) else if (density.isCompact) PaddingValues(start = 10.dp, end = 12.dp) else PaddingValues(start = 12.dp, end = 16.dp)
    BoxPanel(padding = padding, onClick = { change(!enabled) }) {
        Row(Modifier.then(if (tile) Modifier.fillMaxHeight() else Modifier), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(AppIcons.systemProxy, icon, if (enabled) scheme.primary else SurfaceLevel.Block.colorOf(scheme), if (enabled) scheme.onPrimary else scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = if (tile) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Callout.of(true).copy(color = scheme.onSurface))
                if (!density.isCompact) {
                    Text(tr(if (enabled) "Enabled" else "Disabled"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant))
                }
            }
            Box(Modifier.width(SWITCH_TRACK_WIDTH.dp).height(SWITCH_TRACK_HEIGHT.dp)) { CommonSwitch(enabled, change) }
        }
    }
}
