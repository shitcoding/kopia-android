import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_ui/main.dart';

void main() {
  testWidgets('KopiaKt app renders home screen', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: KopiaKtApp()),
    );

    expect(find.text('KopiaKt'), findsOneWidget);
    expect(find.text('KopiaKt Flutter UI'), findsOneWidget);
    expect(find.byIcon(Icons.backup), findsOneWidget);
    expect(find.text('Test Bridge'), findsOneWidget);
  });
}
