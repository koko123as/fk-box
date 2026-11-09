package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.tr
import io.nekohasekai.sfa.database.SubscriptionInfo
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------
// FlClash v0.9.2 widgets/subscription_info_view.dart, widgets/chip_title.dart's
// ExpireChip and dashboard/widgets/profiles.dart's _UsageMeter, fed by the
// provider's subscription-userinfo header saved with each remote profile.
// ---------------------------------------------------------------------------

/** The traffic/expiry a remote profile's provider reported, or null when it sent none. */
fun Json.subscriptionInfo(): SubscriptionInfo? {
    if (this["remote"] != true) return null
    val info = SubscriptionInfo(
        upload = this["upload"].asLong() ?: 0L,
        download = this["download"].asLong() ?: 0L,
        total = this["total"].asLong() ?: 0L,
        expire = this["expire"].asLong() ?: 0L,
    )
    return info.takeUnless { it.isEmpty }
}

/** profile-web-page-url of a remote profile, http/https only (checked natively). */
fun Json.subscriptionWebPage(): String? = (this["webPageUrl"] as? String)?.takeIf { this["remote"] == true && it.isNotEmpty() }

/** Days within which an expiry or a 90% used quota reads as a warning. */
private const val SOON_DAYS = 7
private const val NEARLY_USED_SHARE = 0.9

/** Re-reads the clock each minute so countdowns stay current on a visible page. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun expireMillis(info: SubscriptionInfo): Long? = info.expire.takeIf { it > 0 }?.let {
    if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
}

/** Whole days left until expiry, 0 once it has passed. */
fun subscriptionDaysLeft(info: SubscriptionInfo, now: Long): Long? = expireMillis(info)?.let { max(0L, (it - now) / 86_400_000L) }

/** widgets.dart's DateTime.show: the local calendar date. */
fun subscriptionExpireDate(info: SubscriptionInfo): String? = expireMillis(info)?.let {
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(it))
}

/**
 * Error once the quota is used up or the subscription has expired, warning when
 * 90% is used or it expires within a week, otherwise the primary color.
 */
fun subscriptionColor(info: SubscriptionInfo, scheme: BoxScheme, now: Long): Color {
    val usage = info.usage
    val expire = expireMillis(info)
    return when {
        (usage != null && usage >= 1.0) || (expire != null && expire <= now) -> scheme.error
        (usage != null && usage >= NEARLY_USED_SHARE) || (expire != null && (expire - now) / 86_400_000L < SOON_DAYS) -> scheme.warning
        else -> scheme.primary
    }
}

private fun percentOf(share: Double) = "${(share * 100).toInt().coerceIn(0, 100)}%"

/** ExpireChip: the expiry date on a tinted pill, error once past and warning within a week. */
@Composable
fun SubscriptionExpireChip(info: SubscriptionInfo) {
    val date = subscriptionExpireDate(info) ?: return
    val scheme = BoxTheme.scheme
    val now = rememberNow()
    val expire = expireMillis(info)!!
    val color = when {
        expire <= now -> scheme.error
        (expire - now) / 86_400_000L < SOON_DAYS -> scheme.warning
        else -> scheme.primary
    }
    Box(Modifier.background(color.copy(alpha = 0.15f), AppShape.full).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(date, maxLines = 1, style = AppTextStyle.Caption1.of(true).copy(color = color))
    }
}

/**
 * ChipTitle: a one-line title followed by the expiry chip, which gives way
 * before it would squeeze the title below half the row.
 */
@Composable
fun SubscriptionChipTitle(title: String, info: SubscriptionInfo?, style: TextStyle) {
    val date = info?.let(::subscriptionExpireDate)
    if (info == null || date == null) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
        return
    }
    val measurer = rememberTextMeasurer()
    val chipStyle = AppTextStyle.Caption1.of(true)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val titleWidth = measurer.measure(title, style, maxLines = 1).size.width.toFloat()
        val chipWidth = measurer.measure(date, chipStyle, maxLines = 1).size.width + with(density) { (16 + 8).dp.toPx() }
        val showChip = widthPx - chipWidth >= minOf(titleWidth, widthPx / 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
            if (showChip) SubscriptionExpireChip(info)
        }
    }
}

/** The quota bar: a Material 3 linear progress indicator. */
@Composable
fun SubscriptionProgressBar(share: Double, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { share.toFloat().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = color.copy(alpha = 0.15f),
        drawStopIndicator = {},
    )
}

/** Profile card subtitle: "used / total · remaining" when the provider reported a quota. */
@Composable
fun subscriptionTrafficLine(info: SubscriptionInfo): String? {
    if (info.total <= 0) return null
    return "${bytes(info.used)} / ${bytes(info.total)} · ${tr("Remaining")} ${bytes(info.remaining)}"
}

/**
 * SubscriptionInfoView: used / total with the expiry (when it fits) on one
 * line and the quota bar beneath.
 */
@Composable
fun SubscriptionInfoView(info: SubscriptionInfo) {
    val scheme = BoxTheme.scheme
    val now = rememberNow()
    val usage = info.usage
    val color = subscriptionColor(info, scheme, now)
    val valueStyle = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurfaceVariant)
    val metaStyle = AppTextStyle.Footnote.of().copy(color = scheme.onSurface.copy(alpha = 0.7f))
    val expire = subscriptionExpireDate(info)?.let { date -> "${tr("Expires")} $date · ${daysText(info, now)}" } ?: tr("No expiry")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (info.total > 0) "${bytes(info.used)} / ${bytes(info.total)}" else tr("Traffic not reported"),
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = valueStyle,
            )
            Spacer(Modifier.width(12.dp))
            Text(expire, maxLines = 1, overflow = TextOverflow.Ellipsis, style = metaStyle.copy(color = if (color == scheme.primary) metaStyle.color else color))
        }
        if (usage != null) {
            Spacer(Modifier.height(4.dp))
            SubscriptionProgressBar(usage, color)
        }
    }
}

/** SubscriptionInfoDetailView rows: used, total, remaining, expiry, and the provider's website. */
@Composable
fun SubscriptionInfoSection(info: SubscriptionInfo, webPage: String?, openWebPage: (String) -> Unit) {
    val now = rememberNow()
    val rows = buildList<@Composable (ItemPosition) -> Unit> {
        add { position -> ListItem(title = { SubscriptionInfoView(info) }, position = position) }
        if (info.total > 0) {
            add { position -> ListItem(title = { Text(tr("Used traffic")) }, trailing = { Text(bytes(info.used)) }, position = position) }
            add { position -> ListItem(title = { Text(tr("Total traffic")) }, trailing = { Text(bytes(info.total)) }, position = position) }
            add { position -> ListItem(title = { Text(tr("Remaining traffic")) }, trailing = { Text(bytes(info.remaining)) }, position = position) }
        }
        add { position ->
            val date = subscriptionExpireDate(info)
            ListItem(
                title = { Text(tr("Expire time")) },
                subtitle = if (date != null) ({ Text(daysText(info, now)) }) else null,
                trailing = { Text(date ?: tr("No expiry")) },
                position = position,
            )
        }
        if (webPage != null) {
            add { position ->
                ListItem(title = { Text(tr("Open website")) }, subtitle = { Text(webPage, maxLines = 1, overflow = TextOverflow.Ellipsis) }, position = position, onClick = { openWebPage(webPage) })
            }
        }
    }
    SelectionContainer { Section(title = tr("Subscription info"), rows = rows) }
}

/** Percent used, used / total, and the quota bar. */
@Composable
fun SubscriptionUsageMeter(info: SubscriptionInfo) {
    val share = info.usage ?: return
    val scheme = BoxTheme.scheme
    val now = rememberNow()
    val color = subscriptionColor(info, scheme, now)
    val percent = AppTextStyle.Title3.of(true).tabular.copy(letterSpacing = 0.sp, color = scheme.onSurface)
    val unit = AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percentOf(share), maxLines = 1, style = percent)
                Text(" ${tr("used")}", maxLines = 1, style = unit, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                "${bytes(info.used)} / ${bytes(info.total)}",
                Modifier.weight(1f).padding(bottom = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                style = AppTextStyle.Footnote.of().tabular.copy(color = scheme.onSurfaceVariant),
            )
        }
        Spacer(Modifier.height(8.dp))
        SubscriptionProgressBar(share, color)
    }
}

/** The dashboard's end label: days left (warning within a week) when the provider reported an expiry. */
@Composable
fun subscriptionDaysLabel(info: SubscriptionInfo?): Pair<String, Color?>? {
    info ?: return null
    val now = rememberNow()
    val days = subscriptionDaysLeft(info, now) ?: return null
    val scheme = BoxTheme.scheme
    val expired = (expireMillis(info) ?: Long.MAX_VALUE) <= now
    val color = when {
        expired -> scheme.error
        days < SOON_DAYS -> scheme.warning
        else -> null
    }
    return daysText(info, now) to color
}

@Composable
private fun daysText(info: SubscriptionInfo, now: Long): String {
    val expire = expireMillis(info) ?: return tr("No expiry")
    if (expire <= now) return tr("Expired")
    return tr("%d days left").replace("%d", max(0L, (expire - now) / 86_400_000L).toString())
}
