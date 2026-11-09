import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:b_box_ui/pages/settings.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// On a phone the core tabs stay alive off stage. A page pushed over the home
/// route lifts the covered page's bar items into the root overlay; those of the
/// tabs that are not showing must not be lifted, or their buttons paint over
/// the pushed page as ghosts.
/// Whether any item with [tooltip] paints outside an off-stage subtree.
bool _painted(WidgetTester tester, String tooltip) {
  for (final element in find.byTooltip(tooltip, skipOffstage: false).evaluate()) {
    RenderObject? node = element.renderObject;
    if (node == null || !node.attached) continue;
    var hidden = false;
    for (; node != null; node = node.parent) {
      if (node is RenderOffstage && node.offstage) { hidden = true; break; }
    }
    if (!hidden) return true;
  }
  return false;
}

void main() {
  testWidgets('pushed pages do not show bar items of hidden tabs', (tester) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const channel = MethodChannel('test.tabBarItems');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async => call.method == 'ready' ? {
      'version': 'test', 'lan': {'addresses': <String>[]}, 'coreVersion': 'test', 'dashboard': {'status': 'Stopped', 'modes': []},
      'profiles': [], 'preferences': {'theme': 'light'},
    } : null);
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final events = StreamController<dynamic>.broadcast();
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    events.add({'type': 'groups', 'value': [{'tag': 'Select', 'selected': 'Node A', 'selectable': true, 'items': [
      {'tag': 'Node A', 'type': 'Shadowsocks', 'delay': 43, 'testedAt': 1},
    ]}]});
    await tester.pumpAndSettle();
    expect(find.byTooltip('Dashboard cards'), findsOneWidget);
    // Visit Proxies so its page (and its bar items) is built, then go to Tools.
    await tester.tap(find.text('Proxies').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tools').last);
    await tester.pumpAndSettle();
    expect(find.byTooltip('Dashboard cards'), findsNothing);
    expect(find.byTooltip('Scroll to selected'), findsNothing);

    final closed = openPage(tester.element(find.byType(HomePage)), const ServiceSettingsPage());
    for (var frame = 0; frame < 12; frame++) {
      await tester.pump(const Duration(milliseconds: 40));
      expect(_painted(tester, 'Dashboard cards'), isFalse, reason: 'mid-push frame $frame');
      expect(_painted(tester, 'Scroll to selected'), isFalse, reason: 'mid-push frame $frame');
    }
    await tester.pumpAndSettle();
    expect(_painted(tester, 'Dashboard cards'), isFalse);
    expect(_painted(tester, 'Scroll to selected'), isFalse);
    expect(tester.takeException(), isNull);

    tester.element(find.byType(ServiceSettingsPage)).safeNestedPop();
    await tester.pumpAndSettle();
    await closed;
    // The tab that shows keeps its own items after the pop.
    await tester.tap(find.text('Dashboard').last);
    await tester.pumpAndSettle();
    expect(find.byTooltip('Dashboard cards'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });
}
