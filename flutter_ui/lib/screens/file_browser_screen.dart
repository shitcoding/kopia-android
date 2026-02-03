import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../bridge/kopia_bridge.g.dart';
import '../providers/file_browser_provider.dart';

/// Screen for browsing files in a snapshot.
class FileBrowserScreen extends ConsumerStatefulWidget {
  const FileBrowserScreen({
    super.key,
    required this.snapshotId,
    this.initialPath = '',
  });

  final String snapshotId;
  final String initialPath;

  @override
  ConsumerState<FileBrowserScreen> createState() => _FileBrowserScreenState();
}

class _FileBrowserScreenState extends ConsumerState<FileBrowserScreen> {
  late final ScrollController _scrollController;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
    _scrollController.addListener(_onScroll);

    // Load initial directory
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref
          .read(fileBrowserProvider(widget.snapshotId).notifier)
          .loadDirectory(widget.initialPath);
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 200) {
      ref.read(fileBrowserProvider(widget.snapshotId).notifier).loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(fileBrowserProvider(widget.snapshotId));

    final title = widget.initialPath.isEmpty
        ? '/'
        : widget.initialPath.split('/').last;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.pop(),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.download),
            onPressed: () => context.push(
              '/restore/${widget.snapshotId}',
              extra: widget.initialPath,
            ),
            tooltip: 'Restore this folder',
          ),
        ],
      ),
      body: Column(
        children: [
          // Breadcrumb navigation
          _BreadcrumbRow(
            pathHistory: state.pathHistory,
            onNavigate: (path) {
              if (path != state.currentPath) {
                context.go('/files/${widget.snapshotId}?path=$path');
              }
            },
          ),
          const Divider(height: 1),

          // Content
          Expanded(child: _buildContent(context, state)),
        ],
      ),
    );
  }

  Widget _buildContent(BuildContext context, FileBrowserState state) {
    // Loading state (initial)
    if (state.isLoading && state.entries.isEmpty) {
      return Semantics(
        identifier: 'file_browser_loading',
        child: const Center(child: CircularProgressIndicator()),
      );
    }

    // Error state
    if (state.error != null && state.entries.isEmpty) {
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
                onPressed: () => ref
                    .read(fileBrowserProvider(widget.snapshotId).notifier)
                    .loadDirectory(widget.initialPath),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    // Empty state
    if (state.entries.isEmpty) {
      return Center(
        child: Text(
          'Empty folder',
          style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      );
    }

    // List of entries
    return Semantics(
      identifier: 'file_browser_ready',
      child: ListView.builder(
        controller: _scrollController,
        itemCount: state.entries.length + (state.hasMore ? 1 : 0),
        itemBuilder: (context, index) {
          if (index == state.entries.length) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(16.0),
                child: CircularProgressIndicator(),
              ),
            );
          }

          final entry = state.entries[index];
          return _FileEntryItem(
            entry: entry,
            onTap: () {
              if (entry.type == FileEntryType.directory) {
                final newPath = state.currentPath.isEmpty
                    ? entry.name
                    : '${state.currentPath}/${entry.name}';
                context.go('/files/${widget.snapshotId}?path=$newPath');
              }
            },
          );
        },
      ),
    );
  }
}

/// Breadcrumb navigation row.
class _BreadcrumbRow extends StatelessWidget {
  const _BreadcrumbRow({
    required this.pathHistory,
    required this.onNavigate,
  });

  final List<PathSegment> pathHistory;
  final void Function(String path) onNavigate;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          for (var i = 0; i < pathHistory.length; i++) ...[
            if (i > 0)
              Icon(
                Icons.chevron_right,
                size: 20,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            TextButton(
              onPressed: () => onNavigate(pathHistory[i].fullPath),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                minimumSize: Size.zero,
              ),
              child: Text(
                pathHistory[i].name,
                style: TextStyle(
                  color: i == pathHistory.length - 1
                      ? Theme.of(context).colorScheme.primary
                      : Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// List item for a file or directory entry.
class _FileEntryItem extends StatelessWidget {
  const _FileEntryItem({
    required this.entry,
    required this.onTap,
  });

  final FileEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'file_entry_${entry.name}',
      label: entry.name,  // For screen readers
      child: ListTile(
        leading: Icon(
          _getIcon(),
          color: _getIconColor(context),
        ),
        title: Text(
          entry.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Row(
          children: [
            if (entry.type == FileEntryType.file) ...[
              Text(
                _formatSize(entry.size),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(width: 16),
            ],
            if (entry.modTimeEpochMs != null)
              Text(
                _formatDateTime(entry.modTimeEpochMs!),
                style: Theme.of(context).textTheme.bodySmall,
              ),
          ],
        ),
        trailing: entry.type == FileEntryType.directory
            ? Icon(
                Icons.chevron_right,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              )
            : null,
        onTap: onTap,
      ),
    );
  }

  IconData _getIcon() {
    switch (entry.type) {
      case FileEntryType.directory:
        return Icons.folder;
      case FileEntryType.file:
        return Icons.insert_drive_file;
      case FileEntryType.symlink:
        return Icons.link;
      case FileEntryType.unknown:
        return Icons.help_outline;
    }
  }

  Color _getIconColor(BuildContext context) {
    switch (entry.type) {
      case FileEntryType.directory:
        return Theme.of(context).colorScheme.primary;
      case FileEntryType.symlink:
        return Theme.of(context).colorScheme.tertiary;
      default:
        return Theme.of(context).colorScheme.onSurfaceVariant;
    }
  }

  String _formatDateTime(int epochMs) {
    final dateTime = DateTime.fromMillisecondsSinceEpoch(epochMs);
    final formatter = DateFormat.yMd().add_jm();
    return formatter.format(dateTime);
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${bytes ~/ 1024} KB';
    if (bytes < 1024 * 1024 * 1024) return '${bytes ~/ (1024 * 1024)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }
}
