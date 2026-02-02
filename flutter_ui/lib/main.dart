import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router.dart';
import 'theme/kopia_theme.dart';

/// Entry point for standalone Flutter execution (development/testing).
/// In production, the app is embedded via FlutterFragment.
void main() => runApp(const ProviderScope(child: KopiaKtApp()));

/// Root widget for KopiaKt Flutter UI.
class KopiaKtApp extends StatelessWidget {
  const KopiaKtApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'KopiaKt',
      debugShowCheckedModeBanner: false,
      theme: KopiaTheme.lightTheme,
      darkTheme: KopiaTheme.darkTheme,
      themeMode: ThemeMode.system,
      routerConfig: kopiaRouter,
    );
  }
}

/// Widget factory for FlutterFragment embedding.
/// Called from Android host activity to create the Flutter view.
@pragma('vm:entry-point')
void entryPointForFragment() {
  runApp(const ProviderScope(child: KopiaKtApp()));
}
