import 'package:flutter/services.dart';
import '../widgets.dart';

// views/resources.dart (FlClash): auto update, its interval in hours, and one
// row per geo file with its size, last update, single update, URL edit and
// copy. Only the geo section is carried over; FK-box has no icon sets,
// scripts or rule providers of FlClash's kind.
class ResourcesPage extends StatefulWidget {
  const ResourcesPage({super.key});
  @override State<ResourcesPage> createState() => _ResourcesPageState();
}

class _ResourcesPageState extends State<ResourcesPage> {
  List<Json>? _items;
  final _updating = <String>{};

  @override void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _reload());
  }

  Future<void> _reload() async {
    final box = context.box;
    try {
      final items = rows(await box.call('geo.list'));
      if (mounted) setState(() => _items = items);
    } catch (error) {
      if (mounted) setState(() => _items ??= const []);
      box.report(error);
    }
  }

  /// Returns false when the update failed; the old file stays either way.
  Future<bool> _updateOne(String name, String label, {required bool announce}) async {
    if (_updating.contains(name)) return true;
    final box = context.box;
    final updated = context.tr('Updated'), upToDate = context.tr('Already up to date');
    setState(() => _updating.add(name));
    try {
      final changed = await box.call('geo.update', {'name': name}) == true;
      if (announce) box.message('$label: ${changed ? updated : upToDate}');
      return true;
    } catch (error) {
      box.report('$label: ${BoxController.describe(error)}');
      return false;
    } finally {
      if (mounted) setState(() => _updating.remove(name));
    }
  }

  Future<void> _updateAll() async {
    final current = _items;
    if (current == null) return;
    final box = context.box;
    final updated = context.tr('Updated');
    final results = await Future.wait([
      for (final item in current) _updateOne(item['name'] as String, item['label'] as String, announce: false),
    ]);
    await _reload();
    if (results.every((ok) => ok)) box.message(updated);
  }

  Future<void> _editUrl(Json item) async {
    final box = context.box;
    final label = item['label'] as String;
    final invalid = context.tr('Not a valid HTTP or HTTPS URL');
    final value = await showFloatingDialog<String>(
      context: Navigator.of(context, rootNavigator: true).context,
      child: InputDialog(
        title: '$label · ${context.tr('Download URL')}',
        value: item['url'] as String? ?? '',
        resetValue: item['defaultUrl'] as String? ?? '',
        keyboardType: TextInputType.url,
        validator: (text) => RegExp(r'^https?://[^\s/]+.*$').hasMatch((text ?? '').trim()) ? null : invalid,
      ),
    );
    if (value == null) return;
    try {
      final items = rows(await box.call('geo.setUrl', {'name': item['name'], 'url': value.trim()}));
      if (mounted) setState(() => _items = items);
    } catch (error) { box.report(error); }
  }

  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.state);
    final autoUpdate = box.settings['geoAutoUpdate'] == true;
    final interval = (box.settings['geoUpdateInterval'] as num?)?.toInt() ?? 24;
    final hours = context.tr('hours');
    final items = _items ?? const <Json>[];
    return BoxPage(
      title: 'Resources',
      loading: _items == null,
      actions: [IconButtonData(glyph: AppGlyphs.sync, tooltip: context.tr('Update all'),
        onPressed: items.isEmpty || _updating.isNotEmpty ? null : _updateAll)],
      children: [
        generateSection(items: [
          ListItem.toggle(title: Text(context.tr('Auto update')), value: autoUpdate,
            onChanged: (value) => box.setSetting('geoAutoUpdate', value, restart: false)),
          if (autoUpdate) ListItem.input(
            title: Text(context.tr('Update interval')), trailing: Text('$interval $hours'),
            dialogTitle: context.tr('Update interval'), value: '$interval', suffixText: hours,
            keyboardType: TextInputType.number,
            validator: (text) { final number = int.tryParse(text ?? '') ?? 0; return number >= 1 && number <= 8760 ? null : '1–8760'; },
            onChanged: (value) { final number = int.tryParse(value ?? ''); if (number != null) box.setSetting('geoUpdateInterval', number, restart: false); },
          ),
        ]),
        generateSection(
          title: context.tr('Geo resources'),
          footer: context.tr('Xray and mihomo share these files. A failed update keeps the current file; changes apply when the core next starts.'),
          items: [for (final item in items) _row(context, item)],
        ),
      ],
    );
  }

  Widget _row(BuildContext context, Json item) {
    final box = context.box;
    final name = item['name'] as String, label = item['label'] as String;
    final url = item['url'] as String? ?? '';
    final size = item['size'] as num?;
    final updatedAt = item['updatedAt'] as num?;
    final source = switch (item['source']) {
      'builtin' => context.tr('Built in'),
      'download' => context.tr('Downloaded'),
      null => null,
      _ => 'mihomo',
    };
    final busy = _updating.contains(name);
    return ContextMenuRegion(
      menuItems: [
        if (!busy) PopupMenuItem(glyph: AppGlyphs.sync, label: context.tr('Update'), onPressed: () async {
          await _updateOne(name, label, announce: true);
          await _reload();
        }),
        PopupMenuItem(glyph: AppGlyphs.edit, label: context.tr('Edit'), onPressed: () => _editUrl(item)),
        PopupMenuItem(glyph: AppGlyphs.copy, label: context.tr('Copy link'), onPressed: () async {
          final copied = context.tr('Copied');
          await Clipboard.setData(ClipboardData(text: url));
          box.message(copied);
        }),
      ],
      child: ListItem(
        title: Text(label),
        subtitle: Text(updatedAt != null ? [localDate(updatedAt), ?source].join(' · ') : context.tr('Unknown')),
        trailing: busy ? const SizedBox.square(dimension: 24, child: ActivityIndicator()) : Text(size == null ? '—' : bytes(size)),
        onTap: () => _editUrl(item),
      ),
    );
  }
}
