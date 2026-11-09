import '../widgets.dart';

export 'appearance.dart';
export 'access.dart';
export 'about.dart';
export 'backup.dart';

class NativeToggle extends StatelessWidget {
  const NativeToggle(this.title, this.setting, {super.key, this.restart = true, this.autoRestart = false});
  final String title, setting;
  final bool restart, autoRestart;
  @override Widget build(BuildContext context) {
    final box = context.box;
    return ListItem.toggle(
      title: Text(context.tr(title)), value: box.settings[setting] == true,
      onChanged: box.serviceSettingsChanging || autoRestart && box.transitioning && !box.remote
        ? null : (value) => box.setSetting(setting, value, restart: restart, autoRestart: autoRestart),
    );
  }
}

class GeneralSettingsPage extends StatelessWidget {
  const GeneralSettingsPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'General', children: [
    generateSection(items: [
      ListItem.open(leading: const GlyphIcon(AppGlyphs.vpn), title: Text(context.tr('Service')), widget: const ServiceSettingsPage()),
      ListItem.open(leading: const GlyphIcon(AppGlyphs.cpu), title: Text(context.tr('Core')), widget: const CoreSettingsPage()),
    ]),
  ]);
}

class ServiceSettingsPage extends StatelessWidget {
  const ServiceSettingsPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'Service', loading: context.box.serviceSettingsChanging, children: [
    generateSection(items: [
      const NativeToggle('Allow VPN bypass', 'allowBypass', autoRestart: true),
      const NativeToggle('Auto redirect', 'autoRedirect', autoRestart: true),
      const NativeToggle('Dynamic notification', 'dynamicNotification', autoRestart: true),
      ListItem(title: Text(context.tr('Battery optimization')),
        subtitle: Text(context.tr(context.box.settings['batteryExempt'] == true ? 'Unrestricted' : 'Allow background operation')),
        onTap: () => context.box.act('battery.open')),
    ]),
  ]);
}

class CoreSettingsPage extends StatelessWidget {
  const CoreSettingsPage({super.key});
  @override Widget build(BuildContext context) {
    final box = context.box;
    // One switch per linked core, with its version; a core that is off is
    // skipped by auto and refuses to start. The last one cannot go off.
    final cores = [
      ('sing-box', 'singBoxEnabled', box.coreVersion),
      ('Xray', 'xrayEnabled', box.xrayVersion),
      ('mihomo', 'mihomoEnabled', box.mihomoVersion),
    ];
    final onCount = cores.where((core) => box.settings[core.$2] != false).length;
    final limit = ((box.settings['oomMemoryLimitMB'] as num?)?.toInt() ?? 50).toString();
    return BoxPage(title: 'Core', children: [
      generateSection(title: context.tr('Cores'),
        footer: context.tr('A core that is off is skipped when choosing automatically, and profiles that need it will not start.'),
        items: [
          for (final (name, setting, version) in cores) ListItem.toggle(
            title: Text(name), subtitle: version.isEmpty ? null : Text(version),
            value: box.settings[setting] != false,
            onChanged: box.serviceSettingsChanging || box.settings[setting] != false && onCount == 1
              ? null : (value) => box.setSetting(setting, value, restart: false),
          ),
        ]),
      generateSection(footer: context.tr('Deprecation warnings and memory protection apply to the sing-box core only.'), items: [
        const NativeToggle('Disable deprecation warnings', 'disableDeprecatedWarnings', restart: false),
        const NativeToggle('Enable memory protection', 'oomKillerEnabled'),
        const NativeToggle('Disable core memory killer', 'oomKillerDisabled'),
        // The dialog edits the bare number; "MiB" is only shown beside it.
        ListItem.input(title: Text(context.tr('Memory limit')), dialogTitle: context.tr('Memory limit'),
          value: limit, trailing: Text('$limit MiB'), suffixText: 'MiB',
          keyboardType: TextInputType.number, validator: (value) => _parseMemoryLimit(value ?? '') != null ? null : '10–4096 MiB',
          onChanged: (value) { final limit = _parseMemoryLimit(value ?? ''); if (limit != null) box.setSetting('oomMemoryLimitMB', limit); }),
        const NativeToggle('Power reports', 'powerReportEnabled'),
      ]),
    ]);
  }
}

/// BoxSettings.parseMemoryLimit: spaces and a trailing "MiB"/"MB" tolerated, 10–4096.
int? _parseMemoryLimit(String text) {
  var value = text.trim();
  if (value.endsWith('MiB')) { value = value.substring(0, value.length - 3); } else if (value.endsWith('MB')) { value = value.substring(0, value.length - 2); }
  final number = int.tryParse(value.trim());
  return number != null && number >= 10 && number <= 4096 ? number : null;
}

class RemotePage extends StatefulWidget {
  const RemotePage({super.key});
  @override State<RemotePage> createState() => _RemotePageState();
}
class _RemotePageState extends State<RemotePage> {
  Future<List<Json>>? _servers;
  @override void didChangeDependencies() { super.didChangeDependencies(); _servers ??= _load(); }
  Future<List<Json>> _load() async => rows(await context.box.call('remote.list'));
  Future<void> _edit([Json? server]) async {
    await openForm(context, RemoteForm(initial: server ?? {}));
    if (mounted) setState(() => _servers = _load());
  }
  @override Widget build(BuildContext context) => Scaffold(
    title: context.tr('Remote control'),
    primaryAction: IconButtonData(glyph: AppGlyphs.add, tooltip: context.tr('Add server'), onPressed: _edit),
    body: FutureBuilder<List<Json>>(future: _servers, builder: (context, snapshot) => ListView(
      padding: EdgeInsets.fromLTRB(16, context.contentTopPadding, 16, 24 + context.bottomInset),
      children: [
        if (context.box.remote) ListItem(title: Text(context.box.dashboard['remote'].toString()), trailing: TextButton(
          onPressed: () => context.box.act('remote.disconnect'), child: Text(context.tr('Disconnect')))),
        if (snapshot.hasError) ErrorState(snapshot.error!),
        if (snapshot.hasData && snapshot.data!.isEmpty) const EmptyState('No remote servers'),
        for (final server in snapshot.data ?? <Json>[]) Padding(padding: const EdgeInsets.only(bottom: 12), child: Card(
          onPressed: () => context.box.act('remote.connect', {'id': server['id']}),
          child: ListItem(title: Text(server['name'].toString()), subtitle: Text(server['url'].toString()), trailing: BoxMenu(items: [
            PopupMenuItem(glyph: AppGlyphs.edit, label: context.tr('Edit'), onPressed: () => _edit(server)),
            PopupMenuItem(glyph: AppGlyphs.delete, label: context.tr('Delete'), danger: true, onPressed: () async {
              if (await confirm(context, 'Delete', message: server['name'].toString()) && context.mounted) {
                await context.box.act('remote.delete', {'id': server['id']});
                if (mounted) setState(() => _servers = _load());
              }
            }),
          ])),
        )),
      ],
    )),
  );
}

class RemoteForm extends StatefulWidget {
  const RemoteForm({super.key, required this.initial});
  final Json initial;
  @override State<RemoteForm> createState() => _RemoteFormState();
}
class _RemoteFormState extends State<RemoteForm> {
  late final _name = TextEditingController(text: widget.initial['name'] as String? ?? '');
  late final _url = TextEditingController(text: widget.initial['url'] as String? ?? '');
  late final _secret = TextEditingController(text: widget.initial['secret'] as String? ?? '');
  bool _saving = false;
  @override void dispose() { _name.dispose(); _url.dispose(); _secret.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) => BoxPage(title: 'Remote control', loading: _saving,
    confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'), onPressed: _saving ? null : () async {
      setState(() => _saving = true);
      final saved = await context.box.act('remote.save', {...widget.initial, 'name': _name.text, 'url': _url.text, 'secret': _secret.text});
      if (mounted) {
        setState(() => _saving = false);
        if (saved) context.safeNestedPop();
      }
    }),
    children: [
      TextField(controller: _name, decoration: InputDecoration(hintText: context.tr('Name'))),
      const SizedBox(height: 16),
      TextField(controller: _url, keyboardType: TextInputType.url, decoration: InputDecoration(hintText: context.tr('Address'))),
      const SizedBox(height: 16),
      TextField(controller: _secret, obscureText: true, decoration: InputDecoration(hintText: context.tr('Secret'))),
    ],
  );
}
