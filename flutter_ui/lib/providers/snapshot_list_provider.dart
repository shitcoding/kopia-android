import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/kopia_bridge.g.dart';
import '../services/kopia_service.dart';

/// State for the snapshot list screen
class SnapshotListState {
  const SnapshotListState({
    this.isLoading = false,
    this.snapshots = const [],
    this.selectedSource,
    this.error,
  });

  final bool isLoading;
  final List<SnapshotInfo> snapshots;
  final SourceInfo? selectedSource;
  final String? error;

  SnapshotListState copyWith({
    bool? isLoading,
    List<SnapshotInfo>? snapshots,
    SourceInfo? selectedSource,
    String? error,
    bool clearError = false,
    bool clearSource = false,
  }) {
    return SnapshotListState(
      isLoading: isLoading ?? this.isLoading,
      snapshots: snapshots ?? this.snapshots,
      selectedSource: clearSource ? null : (selectedSource ?? this.selectedSource),
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Notifier for managing snapshot list state
class SnapshotListNotifier extends StateNotifier<SnapshotListState> {
  SnapshotListNotifier() : super(const SnapshotListState()) {
    loadSnapshots();
  }

  Future<void> loadSnapshots({SourceInfo? source}) async {
    state = state.copyWith(isLoading: true, clearError: true);

    try {
      final result = await KopiaService.instance.listSnapshots(source: source);
      // Filter out nulls from the list
      final snapshots = result.whereType<SnapshotInfo>().toList();

      state = state.copyWith(
        isLoading: false,
        snapshots: snapshots,
        selectedSource: source,
      );
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: e.toString(),
      );
    }
  }

  Future<void> refresh() async {
    await loadSnapshots(source: state.selectedSource);
  }
}

/// Provider for snapshot list state
final snapshotListProvider =
    StateNotifierProvider<SnapshotListNotifier, SnapshotListState>(
  (ref) => SnapshotListNotifier(),
);
