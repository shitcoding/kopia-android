import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../services/kopia_service.dart';

/// Home screen for KopiaKt Flutter UI.
/// Includes bridge connectivity test and navigation to connect screen.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String _pingStatus = 'Not tested';
  bool _isTesting = false;

  Future<void> _testBridge() async {
    setState(() {
      _isTesting = true;
      _pingStatus = 'Testing...';
    });

    try {
      final result = await KopiaService.instance.ping();
      setState(() {
        _pingStatus = 'Bridge OK: "$result"';
      });
    } catch (e) {
      setState(() {
        _pingStatus = 'Bridge error: $e';
      });
    } finally {
      setState(() {
        _isTesting = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('KopiaKt'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.backup,
              size: 64,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(height: 16),
            Text(
              'KopiaKt Flutter UI',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 32),

            // Connect Repository button
            FilledButton.icon(
              onPressed: () => context.go('/connect'),
              icon: const Icon(Icons.storage),
              label: const Text('Connect Repository'),
            ),
            const SizedBox(height: 32),

            // Bridge test card
            Card(
              margin: const EdgeInsets.symmetric(horizontal: 32),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    Text(
                      'Bridge Status',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _pingStatus,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: _pingStatus.contains('OK')
                                ? Colors.green
                                : _pingStatus.contains('error')
                                    ? Colors.red
                                    : Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                          ),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton(
                      onPressed: _isTesting ? null : _testBridge,
                      child: _isTesting
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Test Bridge'),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
