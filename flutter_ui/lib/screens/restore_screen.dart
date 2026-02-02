import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../bridge/kopia_bridge.g.dart' as bridge;
import '../providers/restore_provider.dart';

/// Screen for restoring files from a snapshot.
class RestoreScreen extends ConsumerWidget {
  const RestoreScreen({
    super.key,
    required this.snapshotId,
    required this.sourcePath,
  });

  final String snapshotId;
  final String sourcePath;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final params = (snapshotId: snapshotId, sourcePath: sourcePath);
    final state = ref.watch(restoreProvider(params));
    final notifier = ref.read(restoreProvider(params).notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Restore'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.pop(),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            // Source info card
            _InfoCard(
              label: 'Source',
              value: sourcePath.isEmpty ? '/' : sourcePath,
            ),
            const SizedBox(height: 16),

            // Destination card
            _DestinationCard(
              destinationUri: state.destinationUri,
              onPickDestination: state.isIdle ? () => notifier.pickDestination() : null,
            ),
            const SizedBox(height: 32),

            // Progress/Action section
            Expanded(
              child: _buildProgressSection(context, state, notifier),
            ),

            // Error message
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Text(
                  state.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                  textAlign: TextAlign.center,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildProgressSection(
    BuildContext context,
    RestoreState state,
    RestoreNotifier notifier,
  ) {
    final progress = state.progress;

    // Idle state - show start button
    if (state.isIdle) {
      return Center(
        child: FilledButton.icon(
          onPressed: state.destinationUri != null
              ? () => notifier.startRestore()
              : null,
          icon: const Icon(Icons.download),
          label: const Text('Start Restore'),
        ),
      );
    }

    // Preparing state
    if (progress?.state == bridge.RestoreState.preparing) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Preparing restore...'),
          ],
        ),
      );
    }

    // In progress state
    if (progress?.state == bridge.RestoreState.inProgress) {
      final percent = _calculateProgress(progress!);
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            LinearProgressIndicator(value: percent / 100),
            const SizedBox(height: 16),
            Text(
              '${percent.toStringAsFixed(0)}%',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              'Files: ${progress.restoredFiles} / ${progress.totalFiles}',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            Text(
              'Size: ${_formatSize(progress.restoredBytes)} / ${_formatSize(progress.totalBytes)}',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            if (progress.currentFile != null) ...[
              const SizedBox(height: 8),
              Text(
                progress.currentFile!,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            const SizedBox(height: 24),
            OutlinedButton(
              onPressed: () => notifier.cancelRestore(),
              child: const Text('Cancel'),
            ),
          ],
        ),
      );
    }

    // Completed state
    if (progress?.state == bridge.RestoreState.completed) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.check_circle,
              size: 64,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(height: 16),
            Text(
              'Restore Complete!',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              '${progress?.restoredFiles ?? 0} files restored',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => context.pop(),
              child: const Text('Done'),
            ),
          ],
        ),
      );
    }

    // Failed state
    if (progress?.state == bridge.RestoreState.failed) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.error,
              size: 64,
              color: Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 16),
            Text(
              'Restore Failed',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: Theme.of(context).colorScheme.error,
                  ),
            ),
            if (progress?.errorMessage != null) ...[
              const SizedBox(height: 8),
              Text(
                progress!.errorMessage!,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.error,
                    ),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                OutlinedButton(
                  onPressed: () => context.pop(),
                  child: const Text('Cancel'),
                ),
                const SizedBox(width: 16),
                FilledButton(
                  onPressed: () {
                    notifier.reset();
                    notifier.startRestore();
                  },
                  child: const Text('Retry'),
                ),
              ],
            ),
          ],
        ),
      );
    }

    // Cancelled state
    if (progress?.state == bridge.RestoreState.cancelled) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.cancel,
              size: 64,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(height: 16),
            Text(
              'Restore Cancelled',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => context.pop(),
              child: const Text('Go Back'),
            ),
          ],
        ),
      );
    }

    return const SizedBox.shrink();
  }

  double _calculateProgress(bridge.RestoreProgress progress) {
    if (progress.totalBytes > 0) {
      return (progress.restoredBytes / progress.totalBytes) * 100;
    }
    if (progress.totalFiles > 0) {
      return (progress.restoredFiles / progress.totalFiles) * 100;
    }
    return 0;
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${bytes ~/ 1024} KB';
    if (bytes < 1024 * 1024 * 1024) return '${bytes ~/ (1024 * 1024)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }
}

/// Card showing labeled information.
class _InfoCard extends StatelessWidget {
  const _InfoCard({
    required this.label,
    required this.value,
  });

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 4),
            Text(
              value,
              style: Theme.of(context).textTheme.bodyLarge,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}

/// Card for destination selection.
class _DestinationCard extends StatelessWidget {
  const _DestinationCard({
    required this.destinationUri,
    required this.onPickDestination,
  });

  final String? destinationUri;
  final VoidCallback? onPickDestination;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Destination',
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 8),
            if (destinationUri != null) ...[
              Text(
                destinationUri!,
                style: Theme.of(context).textTheme.bodyMedium,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 8),
            ],
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: onPickDestination,
                icon: const Icon(Icons.folder_open),
                label: Text(
                  destinationUri == null
                      ? 'Select Destination'
                      : 'Change Destination',
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
