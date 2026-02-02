import 'package:go_router/go_router.dart';
import 'screens/file_browser_screen.dart';
import 'screens/home_screen.dart';
import 'screens/repository_connect_screen.dart';
import 'screens/restore_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/snapshot_list_screen.dart';
import 'screens/welcome_screen.dart';

/// Router configuration for KopiaKt Flutter UI.
/// Handles navigation between screens within the Flutter module.
final kopiaRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      builder: (context, state) => const WelcomeScreen(),
    ),
    GoRoute(
      path: '/dev',
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
      builder: (context, state) => const SnapshotListScreen(),
    ),
    GoRoute(
      path: '/files/:snapshotId',
      builder: (context, state) {
        final snapshotId = state.pathParameters['snapshotId'] ?? '';
        final path = state.uri.queryParameters['path'] ?? '';
        return FileBrowserScreen(
          snapshotId: snapshotId,
          initialPath: path,
        );
      },
    ),
    GoRoute(
      path: '/settings',
      builder: (context, state) => const SettingsScreen(),
    ),
    GoRoute(
      path: '/restore/:snapshotId',
      builder: (context, state) {
        final snapshotId = state.pathParameters['snapshotId'] ?? '';
        final path = state.extra as String? ?? '';
        return RestoreScreen(
          snapshotId: snapshotId,
          sourcePath: path,
        );
      },
    ),
  ],
);
