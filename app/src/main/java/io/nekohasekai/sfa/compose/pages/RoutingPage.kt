package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bridge.CoreRouting
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.JetBrainsMono
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxEditGuard
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CenteredLoading
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.CommonSwitch
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.FormPage
import io.nekohasekai.sfa.compose.ui.FormRow
import io.nekohasekai.sfa.compose.ui.FormRowTextField
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListFooter
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.ReorderableColumn
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.SortHandle
import io.nekohasekai.sfa.compose.ui.TonalButton
import io.nekohasekai.sfa.compose.ui.TonalChip
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Settings › Routing rules: one rule set per core (bridge/CoreRouting.kt),
// compiled by libbox for the core that runs when the service starts.
// ---------------------------------------------------------------------------

private val coreTitles = mapOf("sing-box" to "sing-box", "xray" to "Xray", "mihomo" to "mihomo")
private val modeTitles = mapOf("prepend" to "Before the profile's rules", "override" to "Replace the profile's rules", "off" to "Off (profile only)")
private val targetTitles = mapOf("config" to "Profile's own", "proxy" to "Proxy", "direct" to "Direct", "block" to "Block", "node" to "Node or group")
private val contentTitles = mapOf("all" to "All traffic", "match" to "Domains, IPs or geo", "preset" to "Preset", "list" to "Rule list URL")
private val networkTitles = mapOf("any" to "TCP and UDP", "tcp" to "TCP", "udp" to "UDP")

/** The labels above, translated once per composition for non-composable callbacks. */
@Composable
private fun translated(titles: Map<String, String>): Map<String, String> = titles.mapValues { tr(it.value) }

private fun storedRouting(box: BoxController): CoreRouting.Rules =
    runCatching { CoreRouting.fromWire(box.settings["routingRules"]) }.getOrDefault(CoreRouting.Rules())

@Composable
private fun targetText(target: CoreRouting.Target): String =
    if (target.type == "node") target.tag.ifBlank { tr("Node or group") } else tr(targetTitles[target.type] ?: target.type)

@Composable
private fun ruleSummary(rule: CoreRouting.Rule): String {
    val what = when (rule.content) {
        "all" -> tr("All traffic")
        "preset" -> tr(CoreRouting.presets.firstOrNull { it.id == rule.preset }?.title ?: rule.preset)
        "list" -> rule.url.substringAfter("://").take(48)
        else -> rule.values.take(3).joinToString(", ") + if (rule.values.size > 3) " +${rule.values.size - 3}" else ""
    }
    val extra = buildList {
        if (rule.network != "any") add(rule.network.uppercase())
        if (rule.ports.isNotBlank()) add(rule.ports)
        if (rule.apps.isNotEmpty()) add(tr("Apps") + " ${rule.apps.size}")
    }
    return (listOf(what) + extra).joinToString(" · ") + " → " + targetText(rule.target)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RoutingSegments(segments: List<Pair<T, String>>, value: T, onChanged: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, (segment, label) ->
            SegmentedButton(
                selected = segment == value,
                onClick = { onChanged(segment) },
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
fun RoutingRulesPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(storedRouting(box)) }
    var draft by remember { mutableStateOf(saved) }
    var core by remember { mutableStateOf("sing-box") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val set = draft.core(core)
    val modeText = translated(modeTitles)
    val targetText = translated(targetTitles)
    val nodeTitle = tr("Node or group")
    val requiredText = tr("Required")
    val copyFromText = tr("Copy from")
    val replaceText = tr("Replace the rules of")
    fun update(next: CoreRouting.CoreSet) {
        draft = draft.with(core, next)
    }
    fun openRule(index: Int?) {
        val rule = index?.let { set.rules[it] } ?: CoreRouting.Rule()
        navigator?.pushPage({ RoutingRuleEditor(box, rule, isNew = index == null, core = core) }) { result ->
            val current = draft.core(core)
            when (result) {
                is CoreRouting.Rule -> update(
                    current.copy(rules = if (index == null) current.rules + result else current.rules.map { if (it.id == result.id) result else it }),
                )
                "delete" -> update(current.copy(rules = current.rules.filter { it.id != rule.id }))
            }
        }
    }
    val presetItems = CoreRouting.presets.map { preset ->
        PopupMenuItem(tr(preset.title), subtitle = tr(preset.description), onPressed = {
            val current = draft.core(core)
            update(current.copy(rules = current.rules + preset.rules()))
        })
    }
    val copyItems = CoreRouting.cores.filter { it != core }.map { from ->
        PopupMenuItem(copyFromText + " " + coreTitles[from], onPressed = {
            scope.launch {
                val count = draft.core(from).rules.size
                val replace = draft.core(core).rules.isEmpty() || dialogs?.confirm(
                    replaceText + " " + coreTitles[core] + "?",
                    "${coreTitles[from]}: $count",
                    "Replace",
                ) == true
                if (replace) draft = CoreRouting.copyFrom(draft, from, core)
            }
        })
    }
    val dirty = draft != saved
    val status by BoxService.routingStatus.collectAsState()
    BoxEditGuard(dirty = dirty, busy = saving) {
        CommonScaffold(
            title = tr("Traffic routing"),
            loading = saving,
            iconActions = listOf(IconAction(AppIcons.add, tr("Add rule"), if (saving) null else ({ openRule(null) }))),
            menuItems = presetItems + copyItems + PopupMenuItem(tr("Preview"), icon = AppIcons.code, onPressed = {
                val snapshot = draft
                navigator?.pushPage({ RoutingPreviewPage(box, snapshot, core) }, null)
            }),
            confirmationAction = IconAction(AppIcons.check, tr("Save"), if (saving || !dirty) null else ({
                saving = true
                error = null
                scope.launch {
                    try {
                        val result = json(box.call { saveRouting(CoreRouting.toWire(draft)) })
                        box.settings = result
                        saved = storedRouting(box)
                        draft = saved
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = BoxController.describe(failure)
                    } finally {
                        saving = false
                    }
                }
            })),
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = contentTopPadding())) {
                    RoutingSegments(CoreRouting.cores.map { it to (coreTitles[it] ?: it) }, core) { core = it }
                    if (error != null) {
                        SelectionContainer {
                            Text(error!!, Modifier.padding(top = 8.dp), style = androidx.compose.material3.LocalTextStyle.current.copy(color = BoxTheme.scheme.error))
                        }
                    }
                    status?.let { current ->
                        Spacer(Modifier.height(8.dp))
                        RoutingStatusRow(current) { navigator?.pushPage({ RoutingStatusPage(box) }, null) }
                    }
                    Spacer(Modifier.height(12.dp))
                    ListItemOptions(tr("Merge mode"), CoreRouting.modes, set.mode, ItemPosition.Start, { modeText[it] ?: it }) {
                        update(set.copy(mode = it))
                    }
                    ListItemOptions(tr("Fallback"), CoreRouting.finals, set.final.type, ItemPosition.End, { type ->
                        if (type == "node" && set.final.type == "node") set.final.tag.ifBlank { nodeTitle } else targetText[type] ?: type
                    }) { type ->
                        if (type == "node") {
                            scope.launch {
                                val tag = dialogs?.input(nodeTitle, set.final.tag, validator = { if (it.isBlank()) requiredText else null })
                                if (!tag.isNullOrBlank()) update(set.copy(final = CoreRouting.Target("node", tag.trim())))
                            }
                        } else {
                            update(set.copy(final = CoreRouting.Target(type)))
                        }
                    }
                    ListFooter(
                        tr("Rules apply to the core that runs the selected profile and are added when the service starts; the profile file is not changed. Long-press or drag the handle to reorder; the first matching rule wins."),
                    )
                    if (core == "xray") {
                        ListFooter(tr("Xray: IP and geoip entries match connections to IP addresses; sniffed domains are matched by domain and geosite entries. Set domainStrategy in the profile to also resolve domains for IP rules."))
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (set.rules.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            EmptyState(tr("No rules"), tr("Add a rule, a preset, or copy the rules of another core from the menu."), icon = AppIcons.rules)
                        }
                    } else {
                        ReorderableColumn(
                            items = set.rules,
                            key = { it.id },
                            onMove = { from, to ->
                                if (!saving) {
                                    val current = draft.core(core)
                                    update(current.copy(rules = CoreRouting.move(current.rules, from, to)))
                                }
                            },
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomInset()),
                            enabled = !saving,
                        ) { index, rule ->
                            ListItem(
                                title = { Text(rule.name.ifBlank { tr(contentTitles[rule.content] ?: rule.content) }, maxLines = 1) },
                                subtitle = { Text(ruleSummary(rule), maxLines = 2) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CommonSwitch(rule.enabled, onChanged = { enabled ->
                                            val current = draft.core(core)
                                            update(current.copy(rules = current.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }))
                                        })
                                        SortHandle()
                                    }
                                },
                                position = ItemPosition.of(index, set.rules.size),
                                onClick = if (saving) null else ({ openRule(index) }),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutingRuleEditor(box: BoxController, initial: CoreRouting.Rule, isNew: Boolean, core: String) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var rule by remember { mutableStateOf(initial) }
    var values by remember { mutableStateOf(initial.values.joinToString("\n")) }
    var apps by remember { mutableStateOf(initial.apps.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    // Outbounds, groups and balancers of the selected profile when it runs on
    // this core (mihomo proxy-provider nodes cannot be rule targets).
    val tags by produceState(emptyList<String>(), core) {
        value = runCatching { box.call { routingTargets(core) } }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }
    val contentText = translated(contentTitles)
    val targetText = translated(targetTitles)
    val networkText = translated(networkTitles)
    val presetText = CoreRouting.presets.associate { it.id to tr(it.title) }
    val nodeTitle = tr("Node or group")
    val requiredText = tr("Required")
    val deleteTitle = tr("Delete rule") + "?"
    fun result(): CoreRouting.Rule = rule.copy(
        name = rule.name.trim(),
        values = if (rule.content == "match") CoreRouting.splitValues(values) else emptyList(),
        apps = CoreRouting.splitValues(apps),
        url = if (rule.content == "list") rule.url.trim() else "",
        preset = if (rule.content == "preset") rule.preset else "",
        ports = rule.ports.trim(),
    )
    val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = BoxTheme.scheme.onSurface)
    BoxEditGuard(dirty = result() != initial, busy = false) {
        FormPage(
            if (isNew) "Add rule" else "Edit rule",
            error = error,
            onSave = {
                val next = result()
                try {
                    CoreRouting.validateRule(next)
                    navigator?.pop(next)
                } catch (failure: IllegalArgumentException) {
                    error = failure.message
                }
            },
        ) {
            Section(count = 2) { index, position ->
                when (index) {
                    0 -> FormRow(tr("Name"), position) {
                        FormRowTextField(rule.name, { rule = rule.copy(name = it) }, hint = tr("Optional"))
                    }
                    else -> ListItemToggle(tr("Enabled"), rule.enabled, position) { rule = rule.copy(enabled = it) }
                }
            }
            Section(title = tr("Match"), count = 1) { _, position ->
                ListItemOptions(tr("Content"), CoreRouting.contents, rule.content, position, { contentText[it] ?: it }) {
                    rule = rule.copy(content = it, preset = if (it == "preset" && rule.preset.isBlank()) CoreRouting.presets.first().id else rule.preset)
                }
            }
            when (rule.content) {
                "match" -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = values,
                        onValueChange = { values = it },
                        minLines = 4,
                        maxLines = 12,
                        label = { Text(tr("Domains, IPs or geo")) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        textStyle = mono,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                    ListFooter(tr("One per line: example.com (and subdomains), full:example.com, keyword:word, regexp:pattern (the whole line), 1.2.3.0/24, geosite:cn, geoip:cn, geoip:private."))
                }
                "preset" -> Section(count = 1) { _, position ->
                    ListItemOptions(tr("Preset"), CoreRouting.presets.map { it.id }, rule.preset, position, { id -> presetText[id] ?: id }) { rule = rule.copy(preset = it) }
                }
                "list" -> {
                    Section(count = 1) { _, position ->
                        FormRow(tr("URL"), position) {
                            FormRowTextField(rule.url, { rule = rule.copy(url = it) }, hint = "https://", keyboardType = KeyboardType.Uri, autocorrect = false)
                        }
                    }
                    ListFooter(tr("AdBlock (||example.com^), hosts, plain domain/CIDR lists and Clash rule lists. Downloaded when the service starts and refreshed daily; the last copy is used when a download fails."))
                }
            }
            Section(title = tr("Action"), count = 1) { _, position ->
                ListItemOptions(tr("Target"), CoreRouting.targets, rule.target.type, position, { type ->
                    if (type == "node" && rule.target.type == "node" && rule.target.tag.isNotBlank()) rule.target.tag else targetText[type] ?: type
                }) { type ->
                    if (type == "node") {
                        scope.launch {
                            val tag = dialogs?.input(nodeTitle, rule.target.tag, validator = { if (it.isBlank()) requiredText else null })
                            if (!tag.isNullOrBlank()) rule = rule.copy(target = CoreRouting.Target("node", tag.trim()))
                        }
                    } else {
                        rule = rule.copy(target = CoreRouting.Target(type))
                    }
                }
            }
            if (rule.target.type == "node" && tags.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(60).forEach { tag ->
                        TonalChip(tag, BoxTheme.scheme.secondaryContainer, BoxTheme.scheme.onSecondaryContainer) {
                            rule = rule.copy(target = CoreRouting.Target("node", tag))
                        }
                    }
                }
            }
            Section(title = tr("Conditions"), count = 2) { index, position ->
                when (index) {
                    0 -> ListItemOptions(tr("Network"), CoreRouting.networks, rule.network, position, { networkText[it] ?: it }) {
                        rule = rule.copy(network = it)
                    }
                    else -> FormRow(tr("Ports"), position, invalid = !CoreRouting.portsValid(rule.ports)) {
                        FormRowTextField(rule.ports, { rule = rule.copy(ports = it) }, hint = "443, 8000-9000", keyboardType = KeyboardType.Ascii, autocorrect = false)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apps,
                onValueChange = { apps = it },
                minLines = 2,
                maxLines = 8,
                label = { Text(tr("Apps (package names)")) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                textStyle = mono,
                modifier = Modifier.fillMaxWidth(),
            )
            ListFooter(tr("One package name per line, such as com.android.chrome. Empty matches every app. Xray cannot match apps; such rules are skipped on Xray."))
            if (!isNew) {
                Spacer(Modifier.height(24.dp))
                TonalButton(tr("Delete rule"), {
                    scope.launch { if (dialogs?.confirm(deleteTitle) == true) navigator?.pop("delete") }
                }, icon = AppIcons.delete)
            }
        }
    }
}

@Composable
private fun RoutingPreviewPage(box: BoxController, draft: CoreRouting.Rules, tabCore: String) {
    val preview by produceState<Result<Json>?>(null, draft) {
        value = runCatching { json(box.call { routingPreview(CoreRouting.toWire(draft)) }) }
            .onFailure { if (it is CancellationException) throw it }
    }
    BoxPage("Preview") {
        val result = preview
        when {
            result == null -> CenteredLoading()
            result.isFailure -> EmptyState(tr("Preview unavailable"), BoxController.describe(result.exceptionOrNull()!!), icon = AppIcons.info, selectable = true)
            else -> {
                val value = result.getOrThrow()
                val core = value["core"].toString()
                val warnings = (value["warnings"] as? List<*>)?.map { it.toString() } ?: emptyList()
                val rowsList = buildList<Pair<String, String>> {
                    add(tr("Profile") to value["profile"].toString())
                    add(tr("Core") to (coreTitles[core] ?: core))
                    add(tr("Rules applied") to value["applied"].toString())
                }
                Section(count = rowsList.size) { index, position ->
                    ListItem(title = { Text(rowsList[index].first) }, trailing = { Text(rowsList[index].second) }, position = position)
                }
                if (core != tabCore) {
                    ListFooter(tr("The selected profile runs on another core, so its rules are shown instead of this tab's."))
                }
                if (value["active"] != true) {
                    ListFooter(tr("No routing changes: the profile starts as it is."))
                }
                if (warnings.isNotEmpty()) {
                    Section(title = tr("Warnings"), count = warnings.size) { index, position ->
                        ListItem(title = { SelectionContainer { Text(warnings[index]) } }, position = position)
                    }
                }
                Section(title = tr("Content handed to the core"), count = 1) { _, position ->
                    ListItem(
                        title = {
                            SelectionContainer {
                                Text(value["content"].toString().take(200_000), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = BoxTheme.scheme.onSurface))
                            }
                        },
                        position = position,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingStatusRow(status: BoxService.RoutingStatus, onClick: () -> Unit) {
    val core = coreTitles[status.core] ?: status.core
    val text = when {
        status.error.isNotEmpty() -> tr("Not applied at the last start; the profile runs without the rules") + " (" + core + ")"
        status.active -> tr("Applied at the last start") + ": " + core + " · " + status.applied + " " + tr("rules") +
            if (status.warnings.isNotEmpty()) " · " + tr("Warnings") + " " + status.warnings.size else ""
        else -> tr("No routing changes at the last start") + " (" + core + ")"
    }
    ListItem(
        title = {
            Text(
                text,
                maxLines = 2,
                style = androidx.compose.material3.LocalTextStyle.current.copy(
                    color = if (status.error.isNotEmpty()) BoxTheme.scheme.error else BoxTheme.scheme.onSurface,
                ),
            )
        },
        position = ItemPosition.StartAndEnd,
        onClick = onClick,
    )
}

@Composable
private fun RoutingStatusPage(box: BoxController) {
    val scope = rememberCoroutineScope()
    val status by BoxService.routingStatus.collectAsState()
    var retrying by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    BoxPage("Routing status") {
        val current = status
        if (current == null) {
            EmptyState(tr("Not started yet"), tr("The status appears after the service starts."), icon = AppIcons.info)
            return@BoxPage
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(current.time))
        val rowsList = listOf(
            tr("Core") to (coreTitles[current.core] ?: current.core),
            tr("Started") to time,
            tr("Rules applied") to if (current.error.isNotEmpty()) "0" else current.applied.toString(),
        )
        Section(count = rowsList.size) { index, position ->
            ListItem(title = { Text(rowsList[index].first) }, trailing = { Text(rowsList[index].second) }, position = position)
        }
        if (current.error.isNotEmpty()) {
            Section(title = tr("Not applied"), count = 1) { _, position ->
                ListItem(
                    title = {
                        SelectionContainer {
                            Text(current.error, style = androidx.compose.material3.LocalTextStyle.current.copy(color = BoxTheme.scheme.error))
                        }
                    },
                    position = position,
                )
            }
            ListFooter(tr("The profile runs without the routing rules. Fix the rules or the network, then reload the service to apply them again."))
            Spacer(Modifier.height(12.dp))
            TonalButton(tr("Reload service"), if (retrying) null else ({
                retrying = true
                failure = null
                scope.launch {
                    try {
                        box.call { reloadService() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failure = BoxController.describe(error)
                    } finally {
                        retrying = false
                    }
                }
            }), icon = AppIcons.refresh)
            failure?.let { ListFooter(it) }
        }
        if (current.warnings.isNotEmpty()) {
            Section(title = tr("Warnings"), count = current.warnings.size) { index, position ->
                ListItem(title = { SelectionContainer { Text(current.warnings[index]) } }, position = position)
            }
        }
    }
}
