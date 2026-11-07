package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.BoxData
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.forClip
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.ChipTone
import io.nekohasekai.sfa.compose.ui.DashboardChip
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.LineChart
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.MaterialShapes
import io.nekohasekai.sfa.compose.ui.ShapeBadge
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/*
 * The desktop dashboard's cards from FlClash be84736 (GPL-3.0):
 * widgets/traffic.dart's SpeedCard, outbound_mode.dart's OutboundModeBar,
 * network_takeover.dart's VpnTile, connection_stats.dart,
 * connections_table.dart and proxy_groups.dart, over BoxController's state.
 */

private const val MIN_SPEED_SCALE = 8.0 * 1024
private const val CHART_BLEED = 3f

// ---------------------------------------------------------------------------
// traffic.dart: SpeedCard
// ---------------------------------------------------------------------------

/** The upload or the download on its own desktop card, with its peak and total. */
@Composable
fun SpeedCard(box: BoxController, upload: Boolean) {
    val scheme = BoxTheme.scheme
    val density = dashboardDensity
    val compact = density.isCompact
    val scale = dashboardScale
    val history = if (upload) box.uploadHistory else box.downloadHistory
    val current = box.rates[if (upload) "upload" else "download"].asDouble() ?: 0.0
    val peak = history.maxOrNull() ?: 0.0
    val show = bytes(current).split(" ")
    val total = box.dashboard[if (upload) "uploadTotal" else "downloadTotal"]?.toString() ?: "0 B"
    val label = tr(if (upload) "Upload" else "Download")
    val figureStyle = if (compact) AppTextStyle.LargeTitle else AppTextStyle.Display
    DashboardCard(Modifier.fillMaxSize(), padding = PaddingValues(0.dp), semanticLabel = label, clip = true) {
        Box(Modifier.fillMaxSize()) {
            LineChart(
                values = history,
                revision = box.sampleRevision,
                capacity = 30,
                minScale = MIN_SPEED_SCALE,
                color = if (upload) DashboardColors.series2 else scheme.primary,
                baselineRatio = 1f,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-CHART_BLEED).dp, y = CHART_BLEED.dp)
                    .layoutBleed(CHART_BLEED)
                    .height((if (compact) 62 else 88).dp)
                    .graphicsLayer {},
            )
            Column(Modifier.padding(dashboardCardPadding(density))) {
                Row(Modifier.fillMaxWidth().height((24 * scale).dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (upload) AppGlyphs.arrowUp else AppGlyphs.arrowDown, size = 18.dp, color = if (upload) scheme.onSurfaceVariant else scheme.primary)
                    Text(label, maxLines = 1, style = DashboardText.cardTitle())
                    Text(
                        String.format(tr("Peak %s"), "${dashboardTraffic(peak)}/s"),
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        style = DashboardText.label(),
                    )
                }
                Spacer(Modifier.height((if (compact) 6 else 10).dp))
                Text(
                    buildAnnotatedString {
                        withStyle(DashboardText.figure(figureStyle).toSpanStyle()) { append(show.first()) }
                        withStyle(DashboardText.unit(AppTextStyle.Subheadline).toSpanStyle()) { append(" ${show.last()}/s") }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = figureStyle.of(true),
                )
                Spacer(Modifier.height(2.dp))
                Text(String.format(tr("Total %s"), total), maxLines = 1, style = DashboardText.label())
            }
        }
    }
}

/** Widens a child by [bleed] dp on each side past the parent, as a Positioned with negative insets. */
private fun Modifier.layoutBleed(bleed: Float): Modifier = this.layout { measurable, constraints ->
    val extra = (bleed * 2).dp.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.minWidth
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width, placeable.height) { placeable.place(0, 0) }
}

// ---------------------------------------------------------------------------
// outbound_mode.dart: OutboundModeBar
// ---------------------------------------------------------------------------

internal val outboundModeLabels = mapOf("rule" to "Rule", "global" to "Global", "direct" to "Direct")
internal val outboundModeGlyphs = mapOf("rule" to AppGlyphs.rules, "global" to AppGlyphs.proxies, "direct" to AppGlyphs.target)

/** The desktop control row's mode switch; its buttons sit as far in from the card's ends as from its edges. */
@Composable
fun OutboundModeBar(box: BoxController) {
    val scheme = BoxTheme.scheme
    val density = dashboardDensity
    val inset = 8f
    val modes = (box.dashboard["modes"] as? List<*>).orEmpty().filterIsInstance<String>()
    val selected = (box.dashboard["mode"] as? String ?: "").lowercase()
    val outer = dashboardCardRadius(density) - inset
    val title = tr("Outbound mode")
    DashboardCard(
        Modifier.fillMaxSize(),
        padding = PaddingValues(start = dashboardCardPaddingStart(density).dp, top = inset.dp, end = inset.dp, bottom = inset.dp),
        semanticLabel = title,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxTitle = maxWidth / 4
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(AppGlyphs.split, size = 18.dp, color = scheme.onSurfaceVariant)
                Text(title, Modifier.widthIn(max = maxTitle), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.cardTitle())
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    modes.forEachIndexed { index, mode ->
                        val key = mode.lowercase()
                        val label = tr(outboundModeLabels[key] ?: mode)
                        ConnectedButton(
                            selected = key == selected,
                            first = index == 0,
                            last = index == modes.lastIndex,
                            outer = outer,
                            inner = 10f,
                            background = DashboardColors.tone,
                            label = label,
                            onTap = { if (key != selected) box.launchAct("mode.set", mapOf("mode" to mode)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { foreground ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                GlyphIcon(outboundModeGlyphs[key] ?: AppGlyphs.split, size = 18.dp, color = foreground)
                                Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = foreground))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// network_takeover.dart: the control row's switch tile
// ---------------------------------------------------------------------------

/**
 * VpnTile: FK-box maps FlClash's Android VPN switch to the System HTTP proxy
 * switch (see the System HTTP proxy card), so the grid's control row carries
 * it in the same tile metrics.
 */
@Composable
fun SystemProxyTile(box: BoxController) {
    val density = dashboardDensity
    val enabled = box.dashboard["systemProxy"] == true
    val compact = density.isCompact
    val change: (Boolean) -> Unit = { box.launchAct("systemProxy.set", mapOf("enabled" to it)) }
    DashboardCard(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        DashboardSwitchRow(
            glyph = dashboardSystemProxyGlyph,
            title = tr("System HTTP proxy"),
            subtitle = if (compact) null else trDashboard(if (enabled) "Enabled" else "Disabled"),
            value = enabled,
            onChanged = change,
            iconSize = if (compact) 36f else 40f,
            padding = if (compact) PaddingValues(start = 10.dp, end = 12.dp) else PaddingValues(start = 12.dp, end = 16.dp),
            radius = dashboardCardRadius(density),
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color.Transparent,
            onTap = { change(!enabled) },
        )
    }
}

// ---------------------------------------------------------------------------
// connections_table.dart and connection_stats.dart
// ---------------------------------------------------------------------------

/** DashboardFeed. FlClash's third feed, DNS queries, has no sing-box source in FK-box. */
enum class DashboardFeed(val label: String) {
    Connections("Live connections"),
    Requests("Recent requests"),
}

private const val MAX_FEED_ROWS = 100
private const val MID_TABLE_WIDTH = 620f
private const val WIDE_TABLE_WIDTH = 880f
private const val CELL_INSET = 8f
private const val TABLE_GAP = 12f
private const val TABLE_HEADER_HEIGHT = 32f
private const val MIN_ROWS = 3

private fun tabHeightOf(density: DashboardDensity): Float = if (density.isCompact) 32f else 36f
private fun rowHeightOf(density: DashboardDensity): Float = if (density.isCompact) 32f else 34f
private fun tabInsetOf(density: DashboardDensity): Float = dashboardCardRadius(density) - tabHeightOf(density) / 2

fun connectionsTableTop(density: DashboardDensity, scale: Float): Float = tabInsetOf(density) + tabHeightOf(density) * scale + TABLE_GAP

/** The grid gives the tables a row once they can show their head and a few connections. */
fun connectionsTableMinHeight(density: DashboardDensity, scale: Float): Float =
    connectionsTableTop(density, scale) + (TABLE_HEADER_HEIGHT + rowHeightOf(density) * MIN_ROWS) * scale

private fun isClosed(connection: Json): Boolean = (connection["closedAt"].asDouble() ?: 0.0) > 0

/** Live connections newest first; recent requests are the closed ones, newest closed first. */
private fun feedItems(box: BoxController, feed: DashboardFeed): List<Json> = when (feed) {
    DashboardFeed.Connections -> box.connections.filter { !isClosed(it) }.sortedByDescending { it["createdAt"].asLong() ?: 0L }
    DashboardFeed.Requests -> box.connections.filter(::isClosed).sortedByDescending { it["closedAt"].asLong() ?: 0L }
}

private fun liveCount(box: BoxController): Long = if (box.connections.isNotEmpty()) {
    box.connections.count { !isClosed(it) }.toLong()
} else {
    box.dashboard["connections"].asLong() ?: 0
}

fun openDashboardFeed(navigator: io.nekohasekai.sfa.compose.ui.BoxNavigator?, box: BoxController, feed: DashboardFeed) {
    navigator?.openForm { ConnectionsPage(box, if (feed == DashboardFeed.Connections) "Active" else "Closed") }
}

/** The counts of the feeds, each opening its list, for the phone and for a desktop page too short for the table. */
@Composable
fun ConnectionStatsCard(box: BoxController, modifier: Modifier = Modifier) {
    if (io.nekohasekai.sfa.compose.pages.LocalPageActive.current) BoxDataSubscription(box, BoxData.Connections)
    val navigator = LocalBoxNavigator.current
    val inset = 8f
    val shape = AppShape.all(dashboardCardRadius(dashboardDensity) - inset)
    val running = box.running
    DashboardCard(modifier, padding = PaddingValues(inset.dp), semanticLabel = tr("Connection stats")) {
        Row(Modifier.fillMaxWidth().then(if (dashboardDensity.isDesktop) Modifier.fillMaxHeight() else Modifier)) {
            for (feed in DashboardFeed.entries) {
                val count = if (!running) 0L else if (feed == DashboardFeed.Connections) liveCount(box) else box.connections.count(::isClosed).toLong()
                Column(
                    Modifier
                        .weight(1f)
                        .then(if (dashboardDensity.isDesktop) Modifier.fillMaxHeight() else Modifier)
                        .clip(shape.forClip())
                        .pressInk(shape, true, tr(feed.label)) { openDashboardFeed(navigator, box, feed) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Text(dashboardCount(count), maxLines = 1, style = DashboardText.figure(AppTextStyle.Title2).copy(letterSpacing = 0.sp))
                    Text(tr(feed.label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.label())
                }
            }
        }
    }
}

private class TableColumn(
    val id: String,
    val label: String,
    val width: Float? = null,
    val flex: Float = 0f,
    val end: Boolean = false,
    val descendingFirst: Boolean = false,
    val sortKey: ((Json) -> Comparable<*>)? = null,
    val cell: @Composable (Json) -> Unit,
)

/** prism_ui's tableColumnWidths without dragged overrides: fixed columns keep their width, flex ones share the rest. */
private fun tableColumnWidths(columns: List<TableColumn>, width: Float): List<Float> {
    val fixed = columns.sumOf { (it.width ?: 0f).toDouble() }.toFloat()
    val flex = columns.filter { it.width == null }.sumOf { it.flex.toDouble() }.toFloat()
    val share = max(0f, width - fixed)
    return columns.map { it.width ?: if (flex == 0f) 0f else share * it.flex / flex }
}

private data class RecordSort(val columnId: String, val descending: Boolean)

private fun nextRecordSort(sort: RecordSort?, column: TableColumn): RecordSort? {
    val first = column.descendingFirst
    if (sort == null || sort.columnId != column.id) return RecordSort(column.id, first)
    if (sort.descending == first) return RecordSort(column.id, !first)
    return null
}

private fun trackerDurationText(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    if (seconds < 60) return "${max(0, seconds)} s"
    val minutes = seconds / 60.0
    if (minutes < 60) return if (minutes < 10) "${String.format(java.util.Locale.ROOT, "%.1f", minutes)} min" else "${Math.round(minutes)} min"
    return "${String.format(java.util.Locale.ROOT, "%.1f", minutes / 60)} h"
}

private fun timeOf(milliseconds: Long?): String =
    if (milliseconds == null || milliseconds <= 0) "—" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(milliseconds))

/** The app a connection came from: its first Android package, else the process path's name. */
private fun sourceOf(connection: Json): String {
    val process = json(connection["process"])
    val packages = (process["packages"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    return packages.firstOrNull() ?: (process["path"] as? String)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: ""
}

/** The node the connection leaves by: the end of sing-box's chain, which FlClash lists first. */
private fun exitOf(connection: Json): String =
    ((connection["chain"] as? List<*>)?.lastOrNull()?.toString() ?: connection["outbound"]?.toString()).orEmpty()

private val builtinOutbounds = setOf("direct", "block", "dns", "reject")

@Composable
private fun MutedText(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = LocalCellStyle.current.copy(color = BoxTheme.scheme.onSurfaceVariant))
}

@Composable
private fun NumberText(text: String, muted: Boolean = false) {
    val style = LocalCellStyle.current.tabular
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (muted) style.copy(color = BoxTheme.scheme.onSurfaceVariant) else style)
}

private val LocalCellStyle = androidx.compose.runtime.staticCompositionLocalOf { TextStyle.Default }

@Composable
private fun SourceCell(name: String) {
    val letter = name.codePoints().limit(1).toArray().let { String(it, 0, it.size) }.ifEmpty { "?" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(DashboardColors.tone2, AppShape.all(7f)), contentAlignment = Alignment.Center) {
            Text(letter, maxLines = 1, style = AppTextStyle.Caption2.of(true).copy(color = BoxTheme.scheme.onSurfaceVariant))
        }
        Text(name.ifEmpty { "—" }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Footnote.of(true).copy(color = BoxTheme.scheme.onSurface))
    }
}

@Composable
private fun TargetCell(connection: Json) {
    val target = (connection["displayDestination"] ?: connection["destination"])?.toString().orEmpty()
    val port = target.substringAfterLast(':', "")
    val host = if (port.isNotEmpty() && port.all { it.isDigit() }) target.substringBeforeLast(':') else target
    val scheme = BoxTheme.scheme
    Text(
        buildAnnotatedString {
            append(host.ifEmpty { "—" })
            if (host != target) withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(":$port") }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = LocalCellStyle.current,
    )
}

@Composable
private fun RuleCell(connection: Json) {
    val rule = connection["rule"]?.toString().orEmpty()
    // sing-box reports the rule and its payload in one string ("domain_suffix=x => route(y)").
    val split = rule.indexOf(' ')
    val scheme = BoxTheme.scheme
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(if (split < 0) rule.ifEmpty { "—" } else rule.substring(0, split)) }
            if (split >= 0) append(rule.substring(split))
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = LocalCellStyle.current,
    )
}

@Composable
private fun ExitCell(connection: Json) {
    val exit = exitOf(connection)
    val builtin = exit.lowercase() in builtinOutbounds || (connection["outboundType"]?.toString()?.lowercase() in builtinOutbounds)
    if (builtin) MutedText(exit.ifEmpty { "—" }) else Text(exit.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = LocalCellStyle.current)
}

@Composable
private fun connectionColumns(width: Float, now: Long): List<TableColumn> {
    val mid = width >= MID_TABLE_WIDTH
    val wide = width >= WIDE_TABLE_WIDTH
    return buildList {
        add(TableColumn("source", trDashboard("Source"), width = if (wide) 160f else 140f, sortKey = { sourceOf(it).lowercase() }) { SourceCell(sourceOf(it)) })
        add(TableColumn("target", tr("Target"), flex = if (wide) 1.3f else 1f, sortKey = { (it["displayDestination"] ?: "").toString().lowercase() }) { TargetCell(it) })
        if (mid) add(TableColumn("protocol", tr("Protocol"), width = if (wide) 52f else 46f, sortKey = { it["network"].toString().lowercase() }) { MutedText(it["network"].toString().uppercase()) })
        if (wide) add(TableColumn("rule", tr("Rule"), flex = 1f, sortKey = { it["rule"].toString().lowercase() }) { RuleCell(it) })
        add(TableColumn("exit", tr("Exit"), width = if (wide) 120f else 100f, sortKey = { exitOf(it).lowercase() }) { ExitCell(it) })
        add(TableColumn("download", tr("Download"), width = if (wide) 80f else 70f, end = true, descendingFirst = true, sortKey = { it["downloadTotal"].asLong() ?: 0L }) { NumberText(dashboardTraffic(it["downloadTotal"].asLong() ?: 0L)) })
        add(TableColumn("upload", tr("Upload"), width = if (wide) 80f else 70f, end = true, descendingFirst = true, sortKey = { it["uploadTotal"].asLong() ?: 0L }) { NumberText(dashboardTraffic(it["uploadTotal"].asLong() ?: 0L)) })
        if (mid) add(TableColumn("duration", tr("Duration"), width = if (wide) 72f else 64f, end = true, descendingFirst = true, sortKey = { -(it["createdAt"].asLong() ?: 0L) }) { NumberText(trackerDurationText(now - (it["createdAt"].asLong() ?: now)), muted = true) })
    }
}

@Composable
private fun requestColumns(): List<TableColumn> = listOf(
    TableColumn("time", tr("Time"), width = 76f, descendingFirst = true, sortKey = { it["createdAt"].asLong() ?: 0L }) { NumberText(timeOf(it["createdAt"].asLong()), muted = true) },
    TableColumn("source", trDashboard("Source"), width = 140f, sortKey = { sourceOf(it).lowercase() }) { SourceCell(sourceOf(it)) },
    TableColumn("target", tr("Target"), flex = 1.2f, sortKey = { (it["displayDestination"] ?: "").toString().lowercase() }) { TargetCell(it) },
    TableColumn("rule", tr("Rule"), flex = 1f, sortKey = { it["rule"].toString().lowercase() }) { RuleCell(it) },
    TableColumn("exit", tr("Exit"), width = 100f, sortKey = { exitOf(it).lowercase() }) { ExitCell(it) },
    TableColumn("traffic", tr("Traffic"), width = 64f, end = true, descendingFirst = true, sortKey = { (it["uploadTotal"].asLong() ?: 0L) + (it["downloadTotal"].asLong() ?: 0L) }) {
        NumberText(dashboardTraffic((it["uploadTotal"].asLong() ?: 0L) + (it["downloadTotal"].asLong() ?: 0L)))
    },
)

@Suppress("UNCHECKED_CAST")
private fun sortRecords(items: List<Json>, columns: List<TableColumn>, sort: RecordSort?): List<Json> {
    val column = columns.firstOrNull { it.id == sort?.columnId } ?: return items
    val key = column.sortKey ?: return items
    val comparator = Comparator<Json> { a, b -> (key(a) as Comparable<Any>).compareTo(key(b) as Any) }
    return if (sort!!.descending) items.sortedWith(comparator.reversed()) else items.sortedWith(comparator)
}

@Composable
private fun TableCells(columns: List<TableColumn>, widths: List<Float>, height: Float, inset: Float, cell: @Composable (Int, TableColumn) -> Unit) {
    Row(Modifier.fillMaxWidth().height(height.dp), verticalAlignment = Alignment.CenterVertically) {
        columns.forEachIndexed { index, column ->
            Box(
                Modifier.width(widths[index].dp).fillMaxHeight().padding(horizontal = inset.dp),
                contentAlignment = if (column.end) Alignment.CenterEnd else Alignment.CenterStart,
            ) { cell(index, column) }
        }
    }
}

@Composable
private fun FeedTable(items: List<Json>, rowHeight: Float, columnsOf: @Composable (Float) -> List<TableColumn>) {
    val scheme = BoxTheme.scheme
    var sort by remember { mutableStateOf<RecordSort?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value
        val columns = columnsOf(width)
        val widths = tableColumnWidths(columns, width)
        val sorted = sortRecords(items, columns, sort)
        val shown = if (sorted.size > MAX_FEED_ROWS) sorted.subList(0, MAX_FEED_ROWS) else sorted
        val headerHeight = TABLE_HEADER_HEIGHT * dashboardScale
        val overflows = shown.size * rowHeight + 2 > maxHeight.value - headerHeight
        val shape = AppShape.all(DashboardCorner.TABLE_HEADER)
        val tone = DashboardColors.tone
        val headerStyle = AppTextStyle.Caption1.of(true).copy(color = scheme.onSurfaceVariant)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(tone, shape).clip(shape.forClip())) {
                TableCells(columns, widths, headerHeight, 0f) { _, column ->
                    val descending = if (sort?.columnId == column.id) sort?.descending else null
                    val color = if (descending == null) headerStyle.color else scheme.onSurface
                    val clickable = column.sortKey != null
                    Row(
                        Modifier
                            .fillMaxSize()
                            .then(if (clickable) Modifier.pressInk(AppShape.none, true) { sort = nextRecordSort(sort, column) } else Modifier)
                            .padding(horizontal = CELL_INSET.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, if (column.end) Alignment.End else Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(column.label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = headerStyle.copy(color = color))
                        if (descending != null) GlyphIcon(if (descending) AppGlyphs.arrowDown else AppGlyphs.arrowUp, size = 12.dp, color = color)
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                    Text(tr("No data"), style = DashboardText.label())
                }
            } else {
                androidx.compose.runtime.CompositionLocalProvider(LocalCellStyle provides AppTextStyle.Footnote.of().copy(color = scheme.onSurface)) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().dashboardFadeOut(overflows, SurfaceLevel.Card.colorOf(scheme)), contentPadding = PaddingValues(top = 2.dp)) {
                        itemsIndexed(shown, key = { _, item -> item["id"].toString() }) { index, item ->
                            Box(Modifier.fillMaxWidth().height(rowHeight.dp).then(if (index % 2 == 1) Modifier.background(tone, shape) else Modifier)) {
                                TableCells(columns, widths, rowHeight, CELL_INSET) { _, column -> column.cell(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionsTableCard(box: BoxController) {
    val pageActive = io.nekohasekai.sfa.compose.pages.LocalPageActive.current
    if (pageActive) BoxDataSubscription(box, BoxData.Connections)
    val density = dashboardDensity
    val scale = dashboardScale
    val rowHeight = rowHeightOf(density) * scale
    val headerHeight = tabHeightOf(density) * scale
    val tabInset = tabInsetOf(density)
    val cellStart = dashboardCardPaddingStart(density) - CELL_INSET
    var feed by remember { mutableStateOf(DashboardFeed.Connections) }
    // Durations tick once a second while the live table shows on the page in view.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(feed, pageActive) {
        while (pageActive && feed == DashboardFeed.Connections) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val running = box.running
    val live = if (running) feedItems(box, DashboardFeed.Connections) else emptyList()
    val requests = if (running) feedItems(box, DashboardFeed.Requests) else emptyList()
    val tcp = live.count { it["network"].toString().lowercase() == "tcp" }
    DashboardCard(
        Modifier.fillMaxSize(),
        padding = PaddingValues(start = cellStart.dp, top = tabInset.dp, end = cellStart.dp),
        semanticLabel = tr("Live connections"),
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(headerHeight.dp).padding(horizontal = (tabInset - cellStart).dp)) {
                val wideHeader = maxWidth.value >= MID_TABLE_WIDTH
                DashboardCappedRow(
                    share = .45f,
                    modifier = Modifier.fillMaxSize(),
                    start = {
                        Row(Modifier.height(headerHeight.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            DashboardFeed.entries.forEachIndexed { index, item ->
                                val count = if (item == DashboardFeed.Connections) live.size else requests.size
                                ConnectedButton(
                                    selected = feed == item,
                                    first = index == 0,
                                    last = index == DashboardFeed.entries.lastIndex,
                                    outer = headerHeight / 2,
                                    inner = 8f,
                                    background = DashboardColors.tone,
                                    label = tr(item.label),
                                    onTap = { feed = item },
                                    modifier = Modifier.fillMaxHeight(),
                                ) { foreground ->
                                    Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(dashboardCount(count.toLong()), maxLines = 1, style = AppTextStyle.Subheadline.of(true).tabular.copy(color = foreground))
                                        Text(tr(item.label), maxLines = 1, style = AppTextStyle.Footnote.of(true).copy(color = foreground))
                                    }
                                }
                            }
                        }
                    },
                    end = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (feed == DashboardFeed.Connections && live.isNotEmpty() && wideHeader) {
                                DashboardChip("TCP $tcp")
                                DashboardChip("UDP ${live.size - tcp}")
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(TABLE_GAP.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (feed) {
                    DashboardFeed.Connections -> FeedTable(live, rowHeight) { width -> connectionColumns(width, now) }
                    DashboardFeed.Requests -> FeedTable(requests, rowHeight) { requestColumns() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// proxy_groups.dart
// ---------------------------------------------------------------------------

private const val STACKED_ICONS = 4
private const val BLOCK_INSET = 10f

/** A group with its current node timed out counts toward the header's warning chip. */
private fun timedOut(group: Json): Boolean {
    val selected = group["selected"]?.toString() ?: return false
    val node = rows(group["items"]).firstOrNull { it["tag"] == selected } ?: return false
    return (node["testedAt"].asLong() ?: 0L) > 0 && (node["delay"].asLong() ?: 0L) <= 0
}

@Composable
private fun GroupMonogram(group: Json, size: Float, radius: Float, ring: androidx.compose.ui.graphics.Color? = null) {
    val scheme = BoxTheme.scheme
    val tag = group["tag"].toString()
    val letters = tag.codePoints().limit(2).toArray().let { String(it, 0, it.size) }.uppercase()
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (ring != null) Box(Modifier.requiredSize((size + 6).dp).background(ring, AppShape.all(radius + 3)))
        Box(Modifier.size(size.dp).background(DashboardColors.tone2, AppShape.all(radius)).padding((size * .15f).dp), contentAlignment = Alignment.Center) {
            Text(letters, maxLines = 1, style = AppTextStyle.Caption1.of(true).copy(color = scheme.onSurfaceVariant))
        }
    }
}

@Composable
private fun GroupsHeader(box: BoxController, groups: List<Json>, count: Boolean) {
    val navigator = LocalBoxNavigator.current
    val mobile = !dashboardDensity.isDesktop
    val timeouts = groups.count(::timedOut)
    DashboardCardHeader(
        title = tr("Proxy groups"),
        glyph = AppGlyphs.layers,
        chevron = mobile && groups.isNotEmpty(),
        leading = {
            if (count && groups.isNotEmpty()) DashboardChip("${groups.size}")
            if (timeouts > 0) DashboardChip(String.format(tr("%d timed out"), timeouts), tone = ChipTone.Warn)
        },
        trailing = if (mobile) null else ({
            Box(Modifier.headerNudge()) {
                DashboardIconButton(AppGlyphs.chevronForward, tr("Proxy groups"), if (groups.isEmpty()) null else ({ navigator?.openForm { ProxiesPage(box) } }))
            }
        }),
    )
}

@Composable
private fun GroupsEmpty(box: BoxController, fit: Boolean) {
    val scheme = BoxTheme.scheme
    Box(Modifier.fillMaxWidth().then(if (fit) Modifier.fillMaxHeight() else Modifier).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 260.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShapeBadge(MaterialShapes.Cookie9Sided, DashboardColors.tone, 64.dp) {
                GlyphIcon(AppGlyphs.layers, size = 26.dp, color = scheme.onSurfaceVariant)
            }
            Text(
                tr(if (box.running) "No proxy groups in this configuration" else "Start the service to view proxy groups"),
                textAlign = TextAlign.Center,
                style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface),
            )
        }
    }
}

/** The desktop card: a tile per group, two columns where they fit, fading out past the card's end. */
@Composable
fun ProxyGroupsCard(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val density = dashboardDensity
    val compact = density.isCompact
    val scale = dashboardScale
    val groups = box.groups
    val tileHeight = (if (compact) 52f else 60f) * scale
    val tileGap = if (compact) 6f else 8f
    val padTop = dashboardCardPaddingTop(density)
    val padStart = dashboardCardPaddingStart(density)
    DashboardCard(
        Modifier.fillMaxSize(),
        padding = PaddingValues(start = padStart.dp, top = padTop.dp, end = padStart.dp),
        semanticLabel = tr("Proxy groups"),
    ) {
        Column(Modifier.fillMaxSize()) {
            GroupsHeader(box, groups, count = true)
            if (groups.isEmpty()) {
                Box(Modifier.weight(1f)) { GroupsEmpty(box, fit = true) }
            } else {
                Spacer(Modifier.height(max(0f, connectionsTableTop(density, scale) - padTop - DASHBOARD_HEADER_HEIGHT * scale).dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = if (maxWidth.value >= 172f * scale * 2 + tileGap) 2 else 1
                    val rowsCount = ceil(groups.size / columns.toDouble()).toInt()
                    val overflows = rowsCount * tileHeight + (rowsCount - 1) * tileGap + padTop > maxHeight.value
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize().dashboardFadeOut(overflows, SurfaceLevel.Card.colorOf(BoxTheme.scheme)),
                        contentPadding = PaddingValues(bottom = padTop.dp),
                        verticalArrangement = Arrangement.spacedBy(tileGap.dp),
                        horizontalArrangement = Arrangement.spacedBy(tileGap.dp),
                    ) {
                        itemsIndexed(groups, key = { _, group -> group["tag"].toString() }) { _, group ->
                            val tag = group["tag"].toString()
                            DashboardBlock(
                                radius = DashboardCorner.BLOCK,
                                modifier = Modifier.fillMaxWidth().height(tileHeight.dp),
                                padding = PaddingValues(start = BLOCK_INSET.dp, end = 14.dp),
                                onTap = { navigator?.openForm { ProxiesPage(box, initialGroup = tag) } },
                                semanticLabel = tag,
                            ) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    GroupMonogram(group, tileHeight - BLOCK_INSET * 2, DashboardCorner.BLOCK - BLOCK_INSET)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                        Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.rowTitle())
                                        Text(group["selected"]?.toString()?.ifEmpty { null } ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.label())
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

@Composable
private fun GroupsFigure(groups: List<Json>) {
    val compact = dashboardDensity.isCompact
    val style = if (compact) AppTextStyle.LargeTitle else AppTextStyle.Display
    val icon = if (compact) 34f else 40f
    val ring = DashboardColors.card
    DashboardCappedRow(
        start = {
            Text(
                buildAnnotatedString {
                    withStyle(DashboardText.figure(style).toSpanStyle()) { append("${groups.size}") }
                    withStyle(DashboardText.unit().toSpanStyle()) { append(" ${tr("Groups")}") }
                },
                maxLines = 1,
                style = style.of(true),
            )
        },
        end = {
            val shown = groups.take(STACKED_ICONS)
            val rest = groups.size - shown.size
            val count = shown.size + if (rest > 0) 1 else 0
            val overlap = 8f
            Box(Modifier.width((count * icon - (count - 1) * overlap).dp).height(icon.dp)) {
                shown.forEachIndexed { index, group ->
                    Box(Modifier.offset(x = (index * (icon - overlap)).dp)) { GroupMonogram(group, icon, DashboardCorner.BADGE, ring) }
                }
                if (rest > 0) {
                    Box(Modifier.offset(x = (shown.size * (icon - overlap)).dp).size(icon.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize((icon + 6).dp).background(ring, AppShape.all(DashboardCorner.BADGE + 3)))
                        Box(Modifier.size(icon.dp).background(DashboardColors.tone, AppShape.all(DashboardCorner.BADGE)), contentAlignment = Alignment.Center) {
                            Text("+$rest", style = AppTextStyle.Caption1.of(true).tabular.copy(color = BoxTheme.scheme.onSurfaceVariant))
                        }
                    }
                }
            }
        },
    )
}

/** The group count, for a desktop page too short for the tiles. */
@Composable
fun ProxyGroupsSummaryCard(box: BoxController) {
    val groups = box.groups
    DashboardCard(Modifier.fillMaxSize(), semanticLabel = tr("Proxy groups")) {
        Column(Modifier.fillMaxSize()) {
            GroupsHeader(box, groups, count = false)
            Spacer(Modifier.weight(1f))
            if (groups.isEmpty()) {
                Text(tr(if (box.running) "No proxy groups in this configuration" else "Core is not running"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.rowTitle())
                if (!box.running) Text(tr("Groups show up here once the core starts"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.label())
            } else {
                GroupsFigure(groups)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
