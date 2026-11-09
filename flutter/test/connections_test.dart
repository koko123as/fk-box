import 'dart:async';
import 'package:fk_box_ui/main.dart';
import 'package:fk_box_ui/widgets.dart';
import 'package:fk_box_ui/pages/connections.dart';
import 'package:fk_box_ui/pages/requests.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('connection subscriptions are released when their page closes', (tester) async {
    // A phone: the desktop dashboard grid's connection stats card holds its own subscription.
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final visibility = <bool>[];
    final events = StreamController<dynamic>.broadcast();
    const channel = MethodChannel('test.connections');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'connections.visible') visibility.add((call.arguments as Map)['visible'] == true);
      return call.method == 'ready' ? {'version': 'test', 'lan': {'addresses': <String>[]}, 'coreVersion': 'test', 'dashboard': {'status': 'Started'}} : null;
    });
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    final context = tester.element(find.byType(HomePage));
    openPage(context, const RequestsPage(initialFeed: 'live'));
    await tester.pumpAndSettle();
    expect(visibility, [true]);
    final pageContext = tester.element(find.byType(ConnectionsFeed));
    pageContext.safeNestedPop();
    await tester.pumpAndSettle();
    await tester.pump();
    expect(find.byType(ConnectionsFeed), findsNothing);
    expect(visibility, [true, false]);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });
}
