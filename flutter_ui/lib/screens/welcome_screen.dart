import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// Welcome screen - entry point for the app.
/// Shows app branding and connect button.
class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              // App icon
              Icon(
                Icons.backup_rounded,
                size: 80,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 24),

              // App title
              Text(
                'KopiaKt',
                style: Theme.of(context).textTheme.displayMedium?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
              ),
              const SizedBox(height: 16),

              // Tagline
              Text(
                'Browse and restore your Kopia backups',
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 48),

              // Connect button
              SizedBox(
                width: MediaQuery.of(context).size.width * 0.8,
                child: FilledButton(
                  key: const Key('connect_repository_button'),
                  onPressed: () => context.go('/connect'),
                  child: Semantics(
                    identifier: 'connect_repository_button',
                    child: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 4.0),
                      child: Text('Connect to Repository'),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
