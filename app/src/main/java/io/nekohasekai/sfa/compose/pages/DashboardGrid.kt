package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.BoxData
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.alphaBlend
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxPanel
import io.nekohasekai.sfa.compose.ui.ChipTone
import io.nekohasekai.sfa.compose.ui.DashboardChip
import io.nekohasekai.sfa.compose.ui.DashboardDensity
import io.nekohasekai.sfa.compose.ui.AppIconButton
import io.nekohasekai.sfa.compose.ui.AppIcon
import io.nekohasekai.sfa.compose.ui.LineChart
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDashboardDensity
import io.nekohasekai.sfa.compose.ui.LocalDashboardScale
import io.nekohasekai.sfa.compose.ui.PanelTitle
import io.nekohasekai.sfa.compose.ui.dashboardCardPadding
import io.nekohasekai.sfa.compose.ui.dashboardCardRadius
import io.nekohasekai.sfa.compose.ui.tr
import kotlin.math.min

/*
 * The tablet / desktop dashboard grid (layout after FlClash's desktop
 * dashboard, GPL-3.0): speed, outbound mode, connection stats and proxy
 * groups over the native state BoxController carries. fk-box has no service-status probe or DNS feed, so the grid has no
 * ServiceStatus card and the stats card has no DNS cell; the profile card
 * takes the freed middle cell.
 */

private const val MAX_COLUMN_WIDTH = 440f

private typealias GridCell = Pair<Int, @Composable () -> Unit>

/** A card spanning [count] columns. */
private fun cell(count: Int, content: @Composable () -> Unit): GridCell = count to content

/** Tab inset + tab + gap + header + three rows. */
private fun groupListMinHeight(density: DashboardDensity, scale: Float): Float {
    val tab = if (density.isCompact) 32f else 36f
    val row = if (density.isCompact) 32f else 34f
    return dashboardCardRadius(density) - tab / 2 + tab * scale + 12f + (32f + row * 3) * scale
}

/**
 * Four columns from 860 dp, else two; every column at most 440 dp (× text scale)
 * with the grid centred. A window too short for the group list, and every two
 * column one, gets the summary cards, so no list scrolls inside a page that scrolls.
 */
@Composable
fun DashboardGrid(box: BoxController, density: DashboardDensity, scale: Float, columns: Int, gap: Dp, top: Dp, bottom: Dp, banners: @Composable () -> Unit, hasBanners: Boolean) {
    val compact = density.isCompact
    val topHeight = ((if (compact) 176 else 208) * scale).dp
    val controlHeight = ((if (compact) 56 else 64) * scale).dp
    val middleHeight = ((if (compact) 152 else 176) * scale).dp
    val summaryHeight = ((if (compact) 112 else 120) * scale).dp
    val listHeight = groupListMinHeight(density, scale).dp
    val modes = (box.dashboard["modes"] as? List<*>).orEmpty().isNotEmpty()
    val systemProxy = box.dashboard["systemProxyAvailable"] == true
    CompositionLocalProvider(LocalDashboardDensity provides density, LocalDashboardScale provides scale) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val startPad = 16.dp
            val endPad = 24.dp
            val room = maxWidth - startPad - endPad - gap * (columns - 1)
            val column = min(room.value / columns, MAX_COLUMN_WIDTH * scale).dp
            val inset = (room - column * columns) / 2
            fun span(count: Int): Dp = column * count + gap * (count - 1)

            @Composable
            fun GridRow(height: Dp, cards: List<GridCell>) {
                Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for ((count, card) in cards) Box(Modifier.width(span(count)).fillMaxHeight()) { card() }
                }
            }

            // The control row: the mode bar beside fk-box's System HTTP proxy tile .
            val controls = buildList<GridCell> {
                val half = columns / 2
                if (modes) add(cell(if (systemProxy && columns == 4) half else columns) { OutboundModeBar(box) })
                if (systemProxy) add(cell(if (modes && columns == 4) half else columns) { SystemProxyPanel(box) })
            }
            val padding = PaddingValues(start = startPad + inset, end = endPad + inset, top = top, bottom = 24.dp + bottom)
            if (columns == 2) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    banners()
                    GridRow(topHeight, listOf(cell(2) { StatusPanel(box) }))
                    GridRow(topHeight, listOf(cell(1) { SpeedCard(box, upload = true) }, cell(1) { SpeedCard(box, upload = false) }))
                    // Narrow grids stack the mode bar and the switches in two columns.
                    for (control in controls) GridRow(controlHeight, listOf(2 to control.second))
                    GridRow(middleHeight, listOf(cell(1) { IntranetPanel(box) }, cell(1) { ProfilePanel(box) }))
                    GridRow(middleHeight, listOf(cell(1) { MemoryPanel(box) }, cell(1) { ProxyGroupsSummaryCard(box) }))
                    GridRow(summaryHeight, listOf(cell(2) { ConnectionStatsCard(box) }))
                }
                return@BoxWithConstraints
            }
            val rowsHeight = top + 24.dp + bottom + topHeight + middleHeight + gap * 2 +
                if (controls.isEmpty()) 0.dp else controlHeight + gap
            val listFits = !hasBanners && maxHeight >= rowsHeight + gap + listHeight
            val fills = !hasBanners && maxHeight >= rowsHeight + gap + summaryHeight
            Column(
                Modifier.fillMaxSize().then(if (fills) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(padding),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                banners()
                GridRow(topHeight, listOf(cell(2) { StatusPanel(box) }, cell(1) { SpeedCard(box, upload = true) }, cell(1) { SpeedCard(box, upload = false) }))
                if (controls.isNotEmpty()) GridRow(controlHeight, controls)
                GridRow(middleHeight, listOf(cell(1) { IntranetPanel(box) }, cell(2) { ProfilePanel(box) }, cell(1) { MemoryPanel(box) }))
                val bottomCards: List<GridCell> = listOf(
                    cell(2) { ConnectionStatsCard(box) },
                    cell(2) { if (listFits) ProxyGroupsCard(box) else ProxyGroupsSummaryCard(box) },
                )
                if (fills) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for ((count, card) in bottomCards) Box(Modifier.width(span(count)).fillMaxHeight()) { card() }
                    }
                } else {
                    GridRow(summaryHeight, bottomCards)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Traffic
// ---------------------------------------------------------------------------

@Composable
private fun SpeedCard(box: BoxController, upload: Boolean) {
    val scheme = BoxTheme.scheme
    val density = LocalDashboardDensity.current
    val compact = density.isCompact
    val scale = LocalDashboardScale.current
    val (padX, padY) = dashboardCardPadding(density)
    val rates = whileActive { box.rates }
    val history = whileActive { if (upload) box.uploadHistory else box.downloadHistory }
    val current = bytes(rates[if (upload) "upload" else "download"] as? Number ?: 0).split(" ")
    val peak = history.maxOrNull() ?: 0.0
    val label = AppTextStyle.Footnote.of().tabular.copy(color = scheme.onSurfaceVariant)
    val figure = if (compact) AppTextStyle.LargeTitle else AppTextStyle.Display
    BoxPanel(padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxSize()) {
            LineChart(
                values = { history },
                capacity = 30,
                minScale = 8.0 * 1024,
                color = if (upload) alphaBlend(scheme.primary.copy(alpha = .45f), SurfaceLevel.Card.colorOf(scheme)) else scheme.primary,
                baselineRatio = 1f,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (compact) 62.dp else 88.dp),
            )
            Column(Modifier.padding(horizontal = padX, vertical = padY)) {
                Row(Modifier.height((24 * scale).dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (upload) AppIcons.arrowUp else AppIcons.arrowDown, size = 18.dp, color = if (upload) scheme.onSurfaceVariant else scheme.primary)
                    Text(tr(if (upload) "Upload" else "Download"), maxLines = 1, style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface))
                    Text(
                        "${tr("Peak")} ${bytes(peak)}/s",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        style = label,
                    )
                }
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(figure.of(true).tabular.copy(letterSpacing = (-.02f * figure.size).sp, color = scheme.onSurface).toSpanStyle()) { append(current.first()) }
                        withStyle(AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurfaceVariant).toSpanStyle()) { append(" ${current.last()}/s") }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = figure.of(true),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${tr("Total")} ${rates[if (upload) "uploadTotal" else "downloadTotal"] ?: "0 B"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = label,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Outbound mode
// ---------------------------------------------------------------------------

/** The control row's mode switch; its buttons sit as far in from the card's ends as from its edges. */
@Composable
private fun OutboundModeBar(box: BoxController) {
    val scheme = BoxTheme.scheme
    val density = LocalDashboardDensity.current
    val inset = 8.dp
    val modes = (box.dashboard["modes"] as? List<*>).orEmpty().filterIsInstance<String>()
    val selected = (box.dashboard["mode"] as? String ?: "").lowercase()
    val labels = mapOf("rule" to "Rule", "global" to "Global", "direct" to "Direct")
    val icons = mapOf("rule" to AppIcons.rules, "global" to AppIcons.proxies, "direct" to AppIcons.target)
    BoxPanel(padding = PaddingValues(start = dashboardCardPadding(density).first, top = inset, end = inset, bottom = inset)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val titleMax = maxWidth / 4
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.split, size = 18.dp, color = scheme.onSurfaceVariant)
                Text(
                    tr("Outbound mode"),
                    Modifier.widthIn(max = titleMax),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface),
                )
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    modes.forEachIndexed { index, mode ->
                        val key = mode.lowercase()
                        val label = tr(labels[key] ?: mode)
                        ConnectedButton(
                            selected = key == selected,
                            background = SurfaceLevel.Block.colorOf(scheme),
                            label = label,
                            onTap = { if (key != selected) box.launchAct { setMode(mode) } },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { foreground ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(icons[key] ?: AppIcons.split, size = 18.dp, color = foreground)
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = foreground))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connection stats
// ---------------------------------------------------------------------------

/**
 * The counts of the live and recent feeds, each opening its list. fk-box has
 * no DNS feed, so the DNS cell is left out. Counts come from the connections
 * feed the Requests page already reads, subscribed while the card is visible.
 */
@Composable
internal fun ConnectionStatsCard(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val density = LocalDashboardDensity.current
    if (LocalPageActive.current) {
        val owner = remember { Any() }
        DisposableEffect(owner) {
            box.setDataVisible(BoxData.Connections, owner, true)
            onDispose { box.setDataVisible(BoxData.Connections, owner, false) }
        }
    }
    val closed = box.connections.count { (it["closedAt"].asDouble() ?: 0.0) > 0 }
    val live = box.connections.size - closed
    val inset = 8.dp
    val shape = AppShape.card
    BoxPanel(padding = PaddingValues(inset)) {
        Row(Modifier.fillMaxWidth().then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
            StatCell(live, "Live connections", shape) { navigator?.openForm { RequestsPage(box, initialFeed = "live") } }
            StatCell(closed, "Recent requests", shape) { navigator?.openForm { RequestsPage(box, initialFeed = "recent") } }
        }
    }
}

@Composable
private fun RowScope.StatCell(count: Int, label: String, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val scheme = BoxTheme.scheme
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(shape).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(dashboardCount(count), maxLines = 1, style = AppTextStyle.Title2.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface))
        Text(tr(label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant))
    }
}

/** Thousands shortened past 9999. */
private fun dashboardCount(count: Int): String = when {
    count < 10_000 -> count.toString()
    count < 1_000_000 -> "${count / 1000}K"
    else -> "${count / 1_000_000}M"
}

// ---------------------------------------------------------------------------
// Proxy groups
// ---------------------------------------------------------------------------

@Composable
private fun GroupsHeader(box: BoxController, count: Boolean) {
    val navigator = LocalBoxNavigator.current
    val groups = box.groups
    val timeout = groups.flatMap { rows(it["items"]) }
        .filter { (it["testedAt"].asDouble() ?: 0.0) > 0 && (it["delay"].asDouble() ?: 0.0) <= 0 }
        .map { it["tag"].toString() }.toSet()
    PanelTitle("Proxy groups", AppIcons.layers, trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (count && groups.isNotEmpty()) DashboardChip("${groups.size}")
            if (timeout.isNotEmpty()) DashboardChip("${tr("Timeout")} ${timeout.size}", tone = ChipTone.Warn)
            Box(Modifier.padding(start = 2.dp)) {
                AppIconButton(AppIcons.chevronForward, tr("Proxy groups"), if (groups.isEmpty()) null else ({ navigator?.openForm { ProxiesPage(box) } }), size = 32.dp, iconSize = 18.dp)
            }
        }
    })
}

@Composable
private fun GroupsEmptySummary(box: BoxController) {
    val scheme = BoxTheme.scheme
    Text(
        tr(if (box.running) "No proxy groups in this configuration" else "Start the service to view proxy groups"),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface),
    )
}

/** The group count, for a desktop page too short for the tiles. */
@Composable
private fun ProxyGroupsSummaryCard(box: BoxController) {
    val scheme = BoxTheme.scheme
    val density = LocalDashboardDensity.current
    val (padX, padY) = dashboardCardPadding(density)
    val groups = box.groups
    val figure = if (density.isCompact) AppTextStyle.LargeTitle else AppTextStyle.Display
    BoxPanel(padding = PaddingValues(horizontal = padX, vertical = padY)) {
        Column(Modifier.fillMaxSize()) {
            GroupsHeader(box, count = false)
            Spacer(Modifier.weight(1f))
            if (groups.isEmpty()) {
                GroupsEmptySummary(box)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(figure.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface).toSpanStyle()) { append("${groups.size}") }
                            withStyle(AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant).toSpanStyle()) { append(" ${tr("Groups")}") }
                        },
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = figure.of(true),
                    )
                    GroupIconStack(groups, size = if (density.isCompact) 34f else 40f)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The tall list of group tiles, for a desktop window with room below the rows. */
@Composable
private fun ProxyGroupsCard(box: BoxController) {
    val scheme = BoxTheme.scheme
    val navigator = LocalBoxNavigator.current
    val density = LocalDashboardDensity.current
    val scale = LocalDashboardScale.current
    val compact = density.isCompact
    val (padX, padY) = dashboardCardPadding(density)
    val tileHeight = ((if (compact) 52 else 60) * scale).dp
    val tileGap = if (compact) 6.dp else 8.dp
    val blockInset = 8.dp
    val groups = box.groups
    BoxPanel(padding = PaddingValues(start = padX, top = padY, end = padX)) {
        Column(Modifier.fillMaxSize()) {
            GroupsHeader(box, count = true)
            Spacer(Modifier.height(12.dp))
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = padY), contentAlignment = Alignment.Center) { GroupsEmptySummary(box) }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = if (maxWidth.value >= 172 * scale * 2 + tileGap.value) 2 else 1
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = padY),
                        verticalArrangement = Arrangement.spacedBy(tileGap),
                        horizontalArrangement = Arrangement.spacedBy(tileGap),
                    ) {
                        items(groups, key = { it["tag"].toString() }) { group ->
                            val tag = group["tag"].toString()
                            val shape = AppShape.card
                            Row(
                                Modifier.fillMaxWidth().height(tileHeight).clip(shape).background(SurfaceLevel.Block.colorOf(scheme))
                                    .clickable { navigator?.openForm { ProxiesPage(box) } }.padding(blockInset),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val icon = tileHeight - blockInset * 2
                                Box(Modifier.size(icon).background(SurfaceLevel.Inset.colorOf(scheme), AppShape.card), contentAlignment = Alignment.Center) {
                                    val letters = tag.codePoints().limit(2).toArray().let { String(it, 0, it.size) }.uppercase()
                                    Text(letters, maxLines = 1, style = AppTextStyle.Caption1.of(true).copy(color = scheme.onSurfaceVariant))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface))
                                    val selected = group["selected"]?.toString().orEmpty()
                                    if (selected.isNotEmpty()) {
                                        Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption1.of().copy(color = scheme.onSurfaceVariant))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
