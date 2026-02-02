import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'screens/home_screen.dart';
import 'screens/repository_connect_screen.dart';

/// Router configuration for KopiaKt Flutter UI.
/// Handles navigation between screens within the Flutter module.
final kopiaRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      builder: (context, state) => const HomeScreen(),
    ),
    GoRoute(
      path: '/connect',
      builder: (context, state) => RepositoryConnectScreen(
        onConnected: () => context.go('/snapshots'),
        onBack: () => context.go('/'),
      ),
    ),
    GoRoute(
      path: '/snapshots',
      builder: (context, state) => const _PlaceholderScreen(
        title: 'Snapshots',
        message: 'Snapshot list will be implemented in Phase 3',
      ),
    ),
    GoRoute(
      path: '/files',
      builder: (context, state) => const _PlaceholderScreen(
        title: 'Files',
        message: 'File browser will be implemented in Phase 3',
      ),
    ),
    GoRoute(
      path: '/settings',
      builder: (context, state) => const _PlaceholderScreen(
        title: 'Settings',
        message: 'Settings will be implemented later',
      ),
    ),
  ],
);

/// Placeholder screen for routes not yet implemented.
class _PlaceholderScreen extends StatelessWidget {
  const _PlaceholderScreen({
    required this.title,
    required this.message,
  });

  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/'),
        ),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.construction,
              size: 64,
              color: Theme.of(context).colorScheme.secondary,
            ),
            const SizedBox(height: 16),
            Text(
              message,
              style: Theme.of(context).textTheme.bodyLarge,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
