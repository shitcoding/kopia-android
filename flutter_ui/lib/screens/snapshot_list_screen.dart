import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../bridge/kopia_bridge.g.dart';
import '../providers/snapshot_list_provider.dart';

/// Screen showing list of available snapshots.
class SnapshotListScreen extends ConsumerWidget {
  const SnapshotListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(snapshotListProvider);
    final notifier = ref.read(snapshotListProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Snapshots'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => notifier.refresh(),
            tooltip: 'Refresh',
          ),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => context.go('/settings'),
            tooltip: 'Settings',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => notifier.refresh(),
        child: _buildContent(context, state, notifier),
      ),
    );
  }

  Widget _buildContent(
    BuildContext context,
    SnapshotListState state,
    SnapshotListNotifier notifier,
  ) {
    // Loading state (initial load)
    if (state.isLoading && state.snapshots.isEmpty) {
      return Semantics(
        identifier: 'snapshot_list_loading',
        child: const Center(child: CircularProgressIndicator()),
      );
    }

    // Error state (no data)
    if (state.error != null && state.snapshots.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.warning,
                size: 48,
                color: Theme.of(context).colorScheme.error,
              ),
              const SizedBox(height: 16),
              Text(
                state.error!,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      color: Theme.of(context).colorScheme.error,
                    ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => notifier.refresh(),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    // Empty state
    if (state.snapshots.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.folder_off,
                size: 48,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(height: 16),
              Text(
                'No snapshots found',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 8),
              Text(
                'Connect to a repository with snapshots',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
      );
    }

    // List of snapshots
    return Semantics(
      identifier: 'snapshot_list_ready',
      child: Stack(
        children: [
          ListView.builder(
            padding: const EdgeInsets.all(16.0),
            itemCount: state.snapshots.length,
            itemBuilder: (context, index) {
              final snapshot = state.snapshots[index];
              return Padding(
                padding: const EdgeInsets.only(bottom: 8.0),
                child: _SnapshotCard(
                  snapshot: snapshot,
                  onTap: () => context.go('/files/${snapshot.id}'),
                ),
              );
            },
          ),
          // Show loading indicator at top when refreshing
          if (state.isLoading && state.snapshots.isNotEmpty)
            const Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: LinearProgressIndicator(),
            ),
        ],
      ),
    );
  }
}

/// Card widget displaying a single snapshot.
class _SnapshotCard extends StatelessWidget {
  const _SnapshotCard({
    required this.snapshot,
    required this.onTap,
  });

  final SnapshotInfo snapshot;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final statsText = snapshot.stats != null
        ? '${snapshot.stats!.totalFileCount} files, ${_formatSize(snapshot.stats!.totalFileSize)}'
        : '';

    return Semantics(
      identifier: 'snapshot_card_${snapshot.id}',
      label: '${_formatSource(snapshot.source)}, $statsText',  // For screen readers
      child: Card(
        elevation: 2,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Source path + incomplete indicator
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _formatSource(snapshot.source),
                        style: Theme.of(context).textTheme.titleMedium,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (snapshot.isIncomplete) ...[
                      const SizedBox(width: 8),
                      Icon(
                        Icons.warning,
                        size: 20,
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 4),

                // Timestamp
                Text(
                  _formatDateTime(snapshot.startTimeEpochMs),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),

                // Stats
                if (snapshot.stats != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    statsText,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ],

                // Description
                if (snapshot.description.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    snapshot.description,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _formatSource(SourceInfo source) {
    return '${source.userName}@${source.host}:${source.path}';
  }

  String _formatDateTime(int epochMs) {
    final dateTime = DateTime.fromMillisecondsSinceEpoch(epochMs);
    final formatter = DateFormat.yMMMd().add_jm();
    return formatter.format(dateTime);
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${bytes ~/ 1024} KB';
    if (bytes < 1024 * 1024 * 1024) return '${bytes ~/ (1024 * 1024)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }
}
