package io.nekohasekai.sfa.compose.ui

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.tabular

/*
 * Record filters (after FlClash's record filter, GPL-3.0). A list page owns one RecordFilterController:
 * values tapped in its rows (RecordFilterScope) become chips in the scaffold
 * header, offered facets fill the Filter menu (or the pinned strip on wide
 * layouts), and a grouper folds the list under headers. The Requests page
 * reuses the same controller for each of its feeds.
 */

const val RECORD_FACET_MAX_OPTIONS = 12

/** The facets fk-box's records carry. */
enum class RecordFacetKind(val title: String) {
    Network("Network"),
    Rule("Rule"),
    Mitm("MITM"),
    App("App"),
    Proxy("Proxy"),
    Level("Level"),
}

enum class RecordGrouping(val id: String, val label: String) {
    App("app", "App"),
    Host("host", "Host"),
    Rule("rule", "Rule"),
    Exit("exit", "Exit"),
    ;

    companion object {
        fun of(id: Any?): RecordGrouping? = entries.firstOrNull { it.id == id }
    }
}

data class RecordFilter(val kind: RecordFacetKind, val value: String)

/** Filters on one facet widen each other, on different facets narrow. */
class RecordFacet<T>(
    val kind: RecordFacetKind,
    val valuesOf: (T) -> List<String>,
    /** Only a facet whose values come from a fixed set is offered in the menu; one growing with the user's apps is filtered from a row. */
    val offered: Boolean = false,
    val order: List<String> = emptyList(),
    private val labelOf: ((String) -> String)? = null,
) {
    fun label(value: String): String = labelOf?.invoke(value) ?: value

    fun optionsOf(items: Iterable<T>, selected: Collection<String> = emptyList(), max: Int = RECORD_FACET_MAX_OPTIONS): List<String> {
        val counts = HashMap<String, Int>()
        for (item in items) for (value in valuesOf(item)) if (value.isNotEmpty()) counts[value] = (counts[value] ?: 0) + 1
        val frequent = counts.keys.sortedByDescending { counts[it] }
        val options = LinkedHashSet<String>().apply {
            addAll(frequent.take(max))
            addAll(selected)
        }.toMutableList()
        fun rank(value: String): Int = order.indexOf(value).let { if (it < 0) order.size else it }
        return options.sortedWith(compareBy<String> { rank(it) }.thenBy { it.lowercase() })
    }
}

class RecordGrouper<T>(val grouping: RecordGrouping, val keyOf: (T) -> String)

/** Top first. */
class RecordGroup<T>(val key: String, val items: List<T>)

/** Groups follow their item shown highest; [reverse] marks a list shown bottom up. */
fun <T> groupRecords(items: List<T>, keyOf: (T) -> String, reverse: Boolean = false): List<RecordGroup<T>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    for (item in if (reverse) items.asReversed() else items) groups.getOrPut(keyOf(item)) { mutableListOf() }.add(item)
    return groups.map { (key, value) -> RecordGroup(key, value) }
}

fun <T> Iterable<T>.whereFilters(facets: List<RecordFacet<T>>, filters: List<RecordFilter>): List<T> {
    if (filters.isEmpty()) return toList()
    val selected = LinkedHashMap<RecordFacet<T>, MutableSet<String>>()
    for (filter in filters) {
        val facet = facets.firstOrNull { it.kind == filter.kind } ?: continue
        selected.getOrPut(facet) { mutableSetOf() }.add(filter.value)
    }
    return filter { item -> selected.all { (facet, values) -> facet.valuesOf(item).any { it in values } } }
}

/** Record filter state: query, facet selections and grouping. */
@Stable
class RecordFilterController<T>(
    val facets: List<RecordFacet<T>>,
    val groupers: List<RecordGrouper<T>> = emptyList(),
    grouping: RecordGrouping? = null,
    private val onGroupingChanged: ((RecordGrouping?) -> Unit)? = null,
) {
    var filters by mutableStateOf<List<RecordFilter>>(emptyList())
        private set
    var grouping by mutableStateOf(grouping?.takeIf { g -> groupers.any { it.grouping == g } })
        private set

    /** Values offered per facet, read before any filter narrows the list. */
    var options by mutableStateOf<Map<RecordFacetKind, List<String>>>(emptyMap())
        private set

    val isFiltering: Boolean get() = filters.isNotEmpty()
    val grouper: RecordGrouper<T>? get() = groupers.firstOrNull { it.grouping == grouping }

    fun facetOf(kind: RecordFacetKind): RecordFacet<T>? = facets.firstOrNull { it.kind == kind }

    fun add(filter: RecordFilter) {
        if (filter.value.isNotEmpty() && filter !in filters && facetOf(filter.kind) != null) filters = filters + filter
    }

    fun remove(filter: RecordFilter) {
        if (filter in filters) filters = filters - filter
    }

    fun toggle(filter: RecordFilter) = if (filter in filters) remove(filter) else add(filter)

    fun clear() {
        filters = emptyList()
    }

    fun changeGrouping(next: RecordGrouping?) {
        if (next == grouping) return
        grouping = next
        onGroupingChanged?.invoke(next)
    }

    /** Offers the values [items] take; call with the list before filtering. */
    fun offer(items: Iterable<T>) {
        val next = LinkedHashMap<RecordFacetKind, List<String>>()
        for (facet in facets) {
            if (!facet.offered) continue
            val values = facet.optionsOf(items, filters.filter { it.kind == facet.kind }.map { it.value })
            if (values.isNotEmpty()) next[facet.kind] = values
        }
        if (next != options) options = next
    }

    fun apply(items: List<T>): List<T> = if (isFiltering) items.whereFilters(facets, filters) else items

    fun groupsOf(items: List<T>, reverse: Boolean = false): List<RecordGroup<T>>? =
        grouper?.keyOf?.let { groupRecords(items, it, reverse) }

    private fun removable(filter: RecordFilter, startsSection: Boolean = false) = ScaffoldKeyword(
        key = filter,
        label = facetOf(filter.kind)?.label(filter.value) ?: filter.value,
        onDeleted = { remove(filter) },
        startsSection = startsSection,
    )

    /** [pinned] keeps every offered value in the strip as a toggle. */
    fun keywords(pinned: Boolean): List<ScaffoldKeyword> {
        if (!pinned) return filters.map { removable(it) }
        val result = mutableListOf<ScaffoldKeyword>()
        for (facet in facets) {
            options[facet.kind].orEmpty().forEachIndexed { index, option ->
                val filter = RecordFilter(facet.kind, option)
                result += ScaffoldKeyword(
                    key = filter,
                    label = facet.label(option),
                    selected = filter in filters,
                    onPressed = { toggle(filter) },
                    startsSection = index == 0 && result.isNotEmpty(),
                )
            }
        }
        filters.filter { options[it.kind]?.contains(it.value) != true }.forEachIndexed { index, filter ->
            result += removable(filter, startsSection = index == 0 && result.isNotEmpty())
        }
        return result
    }

    @Composable
    fun menuItems(pinned: Boolean): List<PopupMenuItem> = buildList {
        if (!pinned && options.isNotEmpty()) {
            val subItems = buildList {
                for (facet in facets) {
                    options[facet.kind].orEmpty().forEachIndexed { index, option ->
                        val filter = RecordFilter(facet.kind, option)
                        add(
                            PopupMenuItem(
                                facet.label(option),
                                checked = filter in filters,
                                section = if (index == 0) PopupMenuSection(tr(facet.kind.title)) else null,
                                onPressed = { toggle(filter) },
                            ),
                        )
                    }
                }
            }
            add(PopupMenuItem(tr("Filter"), AppIcons.filter, subItems = subItems))
        }
        if (groupers.isNotEmpty()) {
            val subItems = buildList {
                add(PopupMenuItem(tr("None"), checked = grouping == null, onPressed = { changeGrouping(null) }))
                for (grouper in groupers) {
                    add(PopupMenuItem(tr(grouper.grouping.label), checked = grouping == grouper.grouping, onPressed = { changeGrouping(grouper.grouping) }))
                }
            }
            add(PopupMenuItem(tr("Group by"), AppIcons.layers, subItems = subItems))
        }
    }
}

/** The grouping a page saved in the `recordGroupings` interface preference. */
fun savedRecordGrouping(preferences: Map<String, Any?>, pageId: String): RecordGrouping? =
    RecordGrouping.of((preferences["recordGroupings"] as? Map<*, *>)?.get(pageId))

/** The `recordGroupings` value with [pageId] set to [grouping], or dropped for none. */
fun recordGroupingsWith(preferences: Map<String, Any?>, pageId: String, grouping: RecordGrouping?): Map<String, String> {
    val current = (preferences["recordGroupings"] as? Map<*, *>).orEmpty()
        .mapNotNull { (key, value) -> if (key is String && value is String && key != pageId) key to value else null }
        .toMap()
    return if (grouping == null) current else current + (pageId to grouping.id)
}

@Composable
fun <T> rememberRecordFilterController(
    facets: List<RecordFacet<T>>,
    groupers: List<RecordGrouper<T>> = emptyList(),
    grouping: RecordGrouping? = null,
    onGroupingChanged: ((RecordGrouping?) -> Unit)? = null,
): RecordFilterController<T> = remember { RecordFilterController(facets, groupers, grouping, onGroupingChanged) }

/** RecordFilterScope: lets a value tapped in a row narrow the list it sits in. */
private val LocalRecordFilter = staticCompositionLocalOf<((RecordFilter) -> Unit)?> { null }

@Composable
fun RecordFilterScope(onFilter: (RecordFilter) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRecordFilter provides onFilter, content = content)
}

/** The tap that adds [filter] to the enclosing list, or null outside a RecordFilterScope or for an empty value. */
@Composable
fun recordFilterAction(filter: RecordFilter): (() -> Unit)? {
    val onFilter = LocalRecordFilter.current ?: return null
    if (filter.value.isEmpty()) return null
    return { onFilter(filter) }
}

/** Which groups the user opened; groups start folded, as SliverCollapsibleGroups' initiallyExpanded = false. */
@Stable
class RecordGroupFolds {
    private var opened by mutableStateOf<Set<String>>(emptySet())
    fun isOpen(key: String): Boolean = key in opened
    fun toggle(key: String) {
        opened = if (key in opened) opened - key else opened + key
    }
}

@Composable
fun rememberRecordGroupFolds(grouping: RecordGrouping?): RecordGroupFolds = remember(grouping) { RecordGroupFolds() }

/** [groups] folded under their headers, newest first whatever way the list reads ungrouped. */
fun <T> LazyListScope.recordGroups(
    groups: List<RecordGroup<T>>,
    folds: RecordGroupFolds,
    keyOf: (T) -> Any,
    trailingOf: ((RecordGroup<T>) -> String?)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    for (group in groups) {
        item(key = "group:" + group.key, contentType = "recordGroupHeader") {
            CollapsibleHeader(
                label = group.key.ifEmpty { tr("Other") },
                count = group.items.size,
                open = folds.isOpen(group.key),
                trailing = trailingOf?.invoke(group),
                onToggle = { folds.toggle(group.key) },
            )
        }
        if (folds.isOpen(group.key)) {
            for (item in group.items) {
                item(key = "group:" + group.key + ":" + keyOf(item), contentType = "record") {
                    itemContent(item)
                }
            }
        }
    }
}

/** A group header: label (count), a trailing note and an expand icon. */
@Composable
fun CollapsibleHeader(label: String, count: Int, open: Boolean, onToggle: (() -> Unit)?, modifier: Modifier = Modifier, trailing: String? = null) {
    val scheme = BoxTheme.scheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(AppShape.card)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(label)
                withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(" ($count)") }
            },
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = AppTextStyle.Headline.of().copy(color = scheme.onSurface),
        )
        if (trailing != null) Text(trailing, style = AppTextStyle.Caption1.of().tabular.copy(color = scheme.onSurfaceVariant))
        CommonExpandIcon(open, size = 20.dp, color = scheme.onSurfaceVariant)
    }
}
