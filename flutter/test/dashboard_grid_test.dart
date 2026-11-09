import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:fk_box_ui/main.dart';
import 'package:fk_box_ui/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// The tablet and desktop dashboard is FlClash's grid (Compose DashboardGrid):
/// fixed-height rows whose cards never overlap or overflow.
void main() {
  setUpAll(() async {
    final root = Platform.environment['FLUTTER_ROOT'];
    if (root != null) {
      final font = File('$root/bin/cache/artifacts/material_fonts/Roboto-Regular.ttf');
      if (font.existsSync()) await (FontLoader('Roboto')..addFont(Future.value(ByteData.sublistView(await font.readAsBytes())))).load();
    }
  });
  for (final (size, fontScale, banners) in [
    (const Size(800, 1000), 1.0, false), (const Size(1100, 1000), 1.3, false),
    (const Size(1280, 800), 1.0, false), (const Size(1280, 1000), 1.0, false), (const Size(1100, 900), 1.0, true),
  ]) {
    testWidgets('dashboard grid at $size scale=$fontScale banners=$banners', (tester) async {
      tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
      addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      const channel = MethodChannel('test.dashboardGrid');
      final visible = <String, bool>{};
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method.endsWith('.visible')) visible[call.method] = (call.arguments as Map)['visible'] == true;
        return call.method == 'ready' ? {
          'version': 'test', 'coreVersion': 'test',
          'dashboard': {'status': 'Stopped', 'modes': ['rule', 'global', 'direct'], 'mode': 'rule', 'systemProxyAvailable': true},
          'profiles': [], 'preferences': {'theme': 'light', 'fontScale': fontScale},
        } : null;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final events = StreamController<dynamic>.broadcast();
      final box = BoxController(channel: channel, events: events.stream);
      final boundaryKey = GlobalKey();
      await tester.pumpWidget(RepaintBoundary(key: boundaryKey, child: BoxApp(controller: box)));
      // The LAN card shimmers until its feed answers, which the events below stand for.
      await tester.pump(const Duration(seconds: 1));
      events.add({'type': 'profiles', 'value': [{'id': 1, 'name': 'Primary sing-box profile', 'remote': true, 'updatedAt': 1793030400000}]});
      events.add({'type': 'dashboard', 'value': {'status': 'Started', 'selectedProfileId': 1, 'systemProxyAvailable': true,
        'modes': ['rule', 'global', 'direct'], 'mode': 'rule', 'uploadTotal': '1.2 MB', 'downloadTotal': '5.4 MB',
        if (banners) 'deprecated': [{'message': 'A deprecated option is in use'}]}});
      events.add({'type': 'lan', 'value': {'addresses': ['192.168.1.20'], 'bindings': [{'type': 'mixed', 'listen': '0.0.0.0', 'port': 2080, 'lan': true}]}});
      events.add({'type': 'memory', 'value': {'total': 120 * 1024 * 1024, 'app': 80 * 1024 * 1024, 'core': 40 * 1024 * 1024, 'running': true}});
      events.add({'type': 'groups', 'value': [for (var i = 0; i < 5; i++) {'tag': 'Group $i', 'selected': 'Node A', 'selectable': true,
        'items': [{'tag': 'Node A', 'type': 'Shadowsocks', 'delay': 43, 'testedAt': 1}]}]});
      events.add({'type': 'rates', 'value': {'upload': 1024, 'download': 8192}});
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(BoxPanel), findsWidgets);
      // The edit action belongs to the phone's list; the grid has no editing.
      expect(find.byTooltip('Dashboard cards'), findsNothing);
      // Every card the grid shows, LAN and memory included, subscribes to its feed.
      expect(visible['dashboard.lan.visible'], isTrue);
      expect(visible['dashboard.memory.visible'], isTrue);
      final rects = [for (final element in find.byType(BoxPanel).evaluate())
        if ((element.renderObject as RenderBox?)?.hasSize == true)
          MatrixUtils.transformRect((element.renderObject! as RenderBox).getTransformTo(null), Offset.zero & (element.renderObject! as RenderBox).size)];
      expect(rects.length, greaterThanOrEqualTo(8), reason: 'status, two speeds, mode, proxy, LAN, profile, memory, stats, groups');
      for (var i = 0; i < rects.length; i++) {
        for (var j = i + 1; j < rects.length; j++) {
          final overlap = rects[i].deflate(.5).intersect(rects[j].deflate(.5));
          expect(overlap.width > 0 && overlap.height > 0, isFalse, reason: 'cards $i ${rects[i]} and $j ${rects[j]} overlap');
        }
      }
      await tester.runAsync(() async {
        final boundary = boundaryKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        final bitmap = await boundary.toImage();
        final data = await bitmap.toByteData(format: ui.ImageByteFormat.png);
        final file = File('build/screenshots/grid-${size.width.toInt()}x${size.height.toInt()}-$fontScale${banners ? '-banners' : ''}.png');
        await file.parent.create(recursive: true);
        await file.writeAsBytes(data!.buffer.asUint8List());
        bitmap.dispose();
      });
      await tester.pumpWidget(const SizedBox());
      await events.close();
    });
  }
}
