import 'dart:async';
import 'package:flutter/foundation.dart' show defaultTargetPlatform;
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'widgets.dart';
import 'pages/dashboard.dart';
import 'pages/proxies.dart';
import 'pages/profiles.dart';
import 'pages/tools.dart';
import 'pages/logs.dart';
import 'pages/requests.dart';
import 'pages/taildrop.dart';
import 'theme_settings.dart';
import 'components/page_switcher.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  runApp(BoxApp(controller: BoxController()));
}

class BoxApp extends StatefulWidget {
  const BoxApp({super.key, required this.controller});
  final BoxController controller;
  @override State<BoxApp> createState() => _BoxAppState();
}

class _BoxAppState extends State<BoxApp> with WidgetsBindingObserver {
  @override void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.controller.initialize();
  }
  @override void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.controller.dispose();
    super.dispose();
  }

  final _themes = <Brightness, ({Object inputs, ThemeData theme})>{};

  @override void didChangeLocales(List<Locale>? locales) => setState(_themes.clear);

  // FlClash theme_manager.dart watchAppTheme: a rebuilt ThemeData never equals the last, and ThemeCrossFade fades on that.
  ThemeData _theme(Brightness brightness) {
    final prefs = widget.controller.preferences;
    final scheme = appColorScheme(widget.controller, brightness);
    final family = prefs['fontFamily'] as String?;
    final locales = WidgetsBinding.instance.platformDispatcher.locales;
    final inputs = (platform: defaultTargetPlatform, fontFamily: family, colorScheme: scheme, locales: locales.join(','));
    if (_themes[brightness] case (inputs: final cached, :final theme) when cached == inputs) return theme;
    final theme = ThemeData(
      colorScheme: scheme,
      fontFamily: family,
      fontFamilyFallback: appFontFamilyFallback(platform: defaultTargetPlatform, locales: locales, fontFamily: family),
      elevatedColorScheme: brightness == Brightness.dark ? scheme.elevated : null,
    );
    _themes[brightness] = (inputs: inputs, theme: theme);
    return theme;
  }

  @override
  Widget build(BuildContext context) => BoxScope(
    controller: widget.controller,
    child: Builder(
      builder: (context) {
        final prefs = BoxScope.of(context, aspect: BoxAspect.appearance).preferences;
        final glass = prefs['liquidGlass'] as String? ?? 'none';
        return LayoutBuilder(builder: (context, constraints) {
          final mobile = constraints.maxWidth <= maxMobileWidth;
          return PrismApp(
            title: 'FK-box',
            debugShowCheckedModeBanner: false,
            onNavigationNotification: (_) => true,
            theme: _theme(Brightness.light),
            darkTheme: _theme(Brightness.dark),
            themeMode: ThemeMode.values.firstWhere((mode) => mode.name == prefs['theme'], orElse: () => ThemeMode.system),
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: const [BoxUiDelegate(), GlobalWidgetsLocalizations.delegate],
            textScale: customFontScale(prefs) ? appTextScale(prefs, 1) : null,
            minTextScale: .8,
            maxTextScale: 1.4,
            // The minimal style draws flat surfaces and never runs the glass shaders.
            liquidGlass: !appearanceTokensOf(prefs).glass ? null : switch (glass) {
              'ultra' => LiquidGlassQuality.ultra,
              'standard' => LiquidGlassQuality.standard,
              _ => null,
            },
            emojiFont: (family: 'Twemoji', pattern: emojiPattern),
            stackedBanners: !mobile,
            builder: (context, child) => ViewScope(
              size: Size(constraints.maxWidth, constraints.maxHeight),
              isMobile: mobile,
              child: _SystemUi(child: _MessageRelay(controller: widget.controller, child: child!)),
            ),
            home: widget.controller.ready ? const HomePage() : Surface(
              color: SurfaceLevel.page.colorOf(context.colorScheme),
              child: SafeArea(child: Center(child: widget.controller.startupError == null
                  ? const SplashScreen()
                  : ErrorState(widget.controller.startupError!, retry: widget.controller.initialize))),
            ),
          );
        });
      },
    ),
  );
}

/// FlClash theme_manager.dart _buildSystemUi: transparent bars drawn over by the pages.
class _SystemUi extends StatelessWidget {
  const _SystemUi({required this.child});
  final Widget child;
  @override Widget build(BuildContext context) {
    final icons = Theme.of(context).brightness == Brightness.dark ? Brightness.light : Brightness.dark;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle(
        statusBarColor: const Color(0x00000000), statusBarIconBrightness: icons,
        systemNavigationBarColor: const Color(0x00000000), systemNavigationBarDividerColor: const Color(0x00000000),
        systemNavigationBarIconBrightness: icons, systemNavigationBarContrastEnforced: true,
      ),
      sized: false,
      child: child,
    );
  }
}

/// Shows the controller's messages in the prism BannerHost (errors in the error style).
class _MessageRelay extends StatefulWidget {
  const _MessageRelay({required this.controller, required this.child});
  final BoxController controller;
  final Widget child;
  @override State<_MessageRelay> createState() => _MessageRelayState();
}

class _MessageRelayState extends State<_MessageRelay> {
  StreamSubscription<BoxMessage>? _messages;
  @override void initState() {
    super.initState();
    _messages = widget.controller.messages.stream.listen((message) {
      if (!mounted || message.text.isEmpty) return;
      BannerHost.maybeOf(context)?.show(context.tr(message.text), level: message.error ? BannerLevel.error : BannerLevel.info);
    });
  }
  @override void dispose() { _messages?.cancel(); super.dispose(); }
  @override Widget build(BuildContext context) => widget.child;
}

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});
  @override State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _opacity;

  @override void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 600));
    _opacity = CurvedAnimation(parent: _controller, curve: Curves.easeIn);
    _controller.forward();
  }

  @override void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _opacity,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Image.asset('assets/images/icon.png', width: 96, height: 96),
          const SizedBox(height: 16),
          const Text('FK-box', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600)),
          const SizedBox(height: 32),
          const SizedBox(width: 120, child: LinearProgressIndicator()),
        ],
      ),
    );
  }
}

/// navigation.dart's NavigationItem: a destination; core pages stay built behind the dock.
typedef _Destination = ({String label, Glyph glyph, bool core});

const _mobileDestinations = <_Destination>[
  (label: 'Dashboard', glyph: AppGlyphs.dashboard, core: true),
  (label: 'Proxies', glyph: AppGlyphs.proxies, core: true),
  (label: 'Profiles', glyph: AppGlyphs.profiles, core: true),
  (label: 'Tools', glyph: AppGlyphs.tools, core: true),
];

/// FlClash v0.9.3 lib/views/navigation.dart: the sidebar adds Requests and Logs.
List<_Destination> _destinationsOf(ViewMode mode) => mode == ViewMode.mobile ? _mobileDestinations : [
  ..._mobileDestinations.take(3),
  (label: 'Requests', glyph: AppGlyphs.requests, core: false),
  (label: 'Logs', glyph: AppGlyphs.logs, core: false),
  _mobileDestinations.last,
];

Widget _destinationPage(String label) => switch (label) {
  'Dashboard' => const DashboardPage(),
  'Proxies' => const ProxiesPage(),
  'Profiles' => const ProfilesPage(),
  'Requests' => const RequestsPage(),
  'Logs' => const LogsPage(),
  _ => const ToolsPage(),
};

/// Dashboard, Proxies, Profiles and Tools, switched by the floating dock or a
/// bar on phones, and by the FlClash sidebar (with Requests and Logs) at laptop
/// and desktop widths, where each destination keeps its own navigator.
class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  String _current = 'Dashboard';
  StreamSubscription<String>? _intents;
  BoxController? _box;
  bool _handlingIntent = false;

  @override void didChangeDependencies() {
    super.didChangeDependencies();
    if (_box != null) return;
    _box = context.box;
    _intents = _box!.intents.stream.listen((_) => _drainIntents());
    WidgetsBinding.instance.addPostFrameCallback((_) => _drainIntents());
  }

  Future<void> _drainIntents() async {
    if (_handlingIntent || !mounted) return;
    _handlingIntent = true;
    try {
      while (mounted && _box!.pendingIntents.isNotEmpty) {
        await _import(_box!.pendingIntents.removeAt(0));
      }
    } finally { _handlingIntent = false; }
  }

  Future<void> _import(String uri) async {
    try {
      if (uri.startsWith('bbox-share:')) {
        if (mounted) await openPage(context, SharePage(token: uri.substring('bbox-share:'.length)));
        return;
      }
      final data = await _box!.call('profiles.decodeIntent', {'uri': uri});
      if (mounted) await openImported(context, data);
    } catch (error) { _box!.report(error); }
  }

  @override void dispose() { _intents?.cancel(); super.dispose(); }

  @override Widget build(BuildContext context) {
    final width = ViewScope.sizeOf(context).width;
    final mode = viewModeOf(width);
    final mobile = mode == ViewMode.mobile;
    final items = _destinationsOf(mode);
    final found = items.indexWhere((item) => item.label == _current);
    // A destination the layout no longer shows (Requests, Logs on a phone) falls back to Tools.
    final index = found < 0 ? items.length - 1 : found;
    final prefs = context.box.preferences;
    final tokens = appearanceTokensOf(prefs);
    final floating = prefs['floatingNavigation'] != false;
    void select(int next) => setState(() => _current = items[next].label);
    final content = PageSwitcher(
      index: index,
      // The minimal style always cross-fades destinations, briefly.
      animation: tokens.expressiveMotion ? tabAnimationOf(prefs) : TabAnimation.fade,
      onSelected: select,
      children: [for (final (i, item) in items.indexed)
        TickerMode(
          key: ValueKey(item.label),
          enabled: i == index,
          child: PageActivityScope(
            isActive: i == index,
            child: item.core || i == index ? KeepScope(
              child: mobile ? _destinationPage(item.label) : Navigator(
                key: ValueKey('${item.label}_navigator'),
                pages: [RootPage(child: _destinationPage(item.label))],
                onDidRemovePage: (_) {},
              ),
            ) : const SizedBox.shrink(),
          ),
        ),
      ],
    );
    final page = SurfaceLevel.page.colorOf(context.colorScheme);
    if (!mobile) {
      final canExpand = canExpandSidebar(mode);
      final expanded = canExpand && prefs['sidebarExpanded'] != false;
      final ltr = Directionality.of(context) == TextDirection.ltr;
      return Surface(color: page, child: Row(children: [
        Surface(
          color: context.colorScheme.chromeSurface,
          shape: BorderDirectional(end: Divider.createBorderSide(context)),
          child: MediaQuery.removePadding(
            context: context, removeLeft: !ltr, removeRight: ltr,
            child: NavigationSidebar(
              destinations: [for (final item in items) SidebarDestination(glyph: item.glyph, label: context.tr(item.label))],
              selectedIndex: index,
              expanded: expanded,
              onSelected: select,
              onToggle: canExpand ? () => context.box.setPreference('sidebarExpanded', !expanded) : null,
            ),
          ),
        ),
        Expanded(child: ClipRect(child: MediaQuery.removePadding(
          context: context, removeLeft: ltr, removeRight: !ltr, child: content,
        ))),
      ]));
    }
    return Surface(color: page, child: Column(children: [
      Expanded(child: Stack(children: [
        Positioned.fill(child: BottomInsetScope(
          inset: floating ? NavigationDock.insetOf(context) : 0,
          child: MediaQuery.removePadding(context: context, removeBottom: true, removeLeft: true, removeRight: true, child: content),
        )),
        PositionedDirectional(
          start: 0, end: 0, bottom: 0,
          child: AnimatedVisibility.bottomNavigation(
            visible: floating,
            child: BarItemPresence.held(child: MediaQuery.removePadding(
              context: context, removeTop: true, removeLeft: true, removeRight: true,
              child: NavigationDock(
                selectedIndex: index,
                onSelected: select,
                destinations: [for (final item in items) NavigationDockDestination(glyph: item.glyph, label: context.tr(item.label))],
              ),
            )),
          ),
        ),
      ])),
      AnimatedVisibility.bottomNavigation(
        visible: !floating,
        child: MediaQuery.removePadding(
          context: context, removeTop: true, removeLeft: true, removeRight: true,
          child: NavigationBar(
            selectedIndex: index,
            onDestinationSelected: select,
            destinations: [for (final (i, item) in items.indexed)
              NavigationDestination(icon: AnimatedGlyph(glyph: item.glyph, filled: i == index), label: context.tr(item.label)),
            ],
          ),
        ),
      ),
    ]));
  }
}
