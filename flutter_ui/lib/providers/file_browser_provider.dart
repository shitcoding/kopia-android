import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/kopia_bridge.g.dart';
import '../services/kopia_service.dart';

/// Represents a breadcrumb segment in the path navigation.
class PathSegment {
  const PathSegment(this.name, this.fullPath);
  final String name;
  final String fullPath;
}

/// State for the file browser screen.
class FileBrowserState {
  const FileBrowserState({
    this.isLoading = false,
    this.currentPath = '',
    this.entries = const [],
    this.pathHistory = const [],
    this.error,
    this.pageToken,
    this.hasMore = false,
  });

  final bool isLoading;
  final String currentPath;
  final List<FileEntry> entries;
  final List<PathSegment> pathHistory;
  final String? error;
  final String? pageToken;
  final bool hasMore;

  FileBrowserState copyWith({
    bool? isLoading,
    String? currentPath,
    List<FileEntry>? entries,
    List<PathSegment>? pathHistory,
    String? error,
    String? pageToken,
    bool? hasMore,
    bool clearError = false,
  }) {
    return FileBrowserState(
      isLoading: isLoading ?? this.isLoading,
      currentPath: currentPath ?? this.currentPath,
      entries: entries ?? this.entries,
      pathHistory: pathHistory ?? this.pathHistory,
      error: clearError ? null : (error ?? this.error),
      pageToken: pageToken ?? this.pageToken,
      hasMore: hasMore ?? this.hasMore,
    );
  }
}

/// Notifier for managing file browser state.
class FileBrowserNotifier extends StateNotifier<FileBrowserState> {
  FileBrowserNotifier(this.snapshotId) : super(const FileBrowserState());

  final String snapshotId;

  Future<void> loadDirectory(String path) async {
    state = state.copyWith(
      isLoading: true,
      clearError: true,
      currentPath: path,
      pathHistory: _buildPathHistory(path),
    );

    try {
      final result = await KopiaService.instance.listDirectory(
        snapshotId: snapshotId,
        path: path,
        pageSize: 100,
      );

      final entries = result.entries.whereType<FileEntry>().toList();

      state = state.copyWith(
        isLoading: false,
        entries: entries,
        pageToken: result.nextPageToken,
        hasMore: result.nextPageToken != null,
      );
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: e.toString(),
      );
    }
  }

  Future<void> loadMore() async {
    if (!state.hasMore || state.isLoading || state.pageToken == null) return;

    state = state.copyWith(isLoading: true);

    try {
      final result = await KopiaService.instance.listDirectory(
        snapshotId: snapshotId,
        path: state.currentPath,
        pageSize: 100,
        pageToken: state.pageToken,
      );

      final newEntries = result.entries.whereType<FileEntry>().toList();

      state = state.copyWith(
        isLoading: false,
        entries: [...state.entries, ...newEntries],
        pageToken: result.nextPageToken,
        hasMore: result.nextPageToken != null,
      );
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: e.toString(),
      );
    }
  }

  List<PathSegment> _buildPathHistory(String path) {
    if (path.isEmpty) {
      return [const PathSegment('/', '')];
    }

    final segments = <PathSegment>[const PathSegment('/', '')];
    var currentPath = '';

    for (final part in path.split('/').where((p) => p.isNotEmpty)) {
      currentPath = currentPath.isEmpty ? part : '$currentPath/$part';
      segments.add(PathSegment(part, currentPath));
    }

    return segments;
  }
}

/// Provider family for file browser state, keyed by snapshot ID.
final fileBrowserProvider = StateNotifierProvider.family<FileBrowserNotifier,
    FileBrowserState, String>(
  (ref, snapshotId) => FileBrowserNotifier(snapshotId),
);
