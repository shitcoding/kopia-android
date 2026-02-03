import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/kopia_bridge.g.dart';
import '../bridge/kopia_bridge.g.dart' as bridge;
import '../services/kopia_service.dart';

/// State for the restore screen.
class RestoreState {
  const RestoreState({
    this.destinationUri,
    this.progress,
    this.error,
  });

  final String? destinationUri;
  final RestoreProgress? progress;
  final String? error;

  RestoreState copyWith({
    String? destinationUri,
    RestoreProgress? progress,
    String? error,
    bool clearDestination = false,
    bool clearError = false,
  }) {
    return RestoreState(
      destinationUri: clearDestination ? null : (destinationUri ?? this.destinationUri),
      progress: progress ?? this.progress,
      error: clearError ? null : (error ?? this.error),
    );
  }

  /// Returns true if restore is in a terminal state.
  bool get isComplete =>
      progress?.state == bridge.RestoreState.completed ||
      progress?.state == bridge.RestoreState.failed ||
      progress?.state == bridge.RestoreState.cancelled;

  /// Returns true if restore is in progress.
  bool get isInProgress =>
      progress?.state == bridge.RestoreState.preparing ||
      progress?.state == bridge.RestoreState.inProgress;

  /// Returns true if restore is idle (can start).
  bool get isIdle => progress?.state == bridge.RestoreState.idle || progress == null;
}

/// Notifier for managing restore state.
class RestoreNotifier extends StateNotifier<RestoreState> {
  RestoreNotifier({
    required this.snapshotId,
    required this.sourcePath,
  }) : super(const RestoreState());

  final String snapshotId;
  final String sourcePath;

  StreamSubscription<RestoreProgress>? _progressSubscription;

  Future<void> pickDestination() async {
    try {
      final result = await KopiaService.instance.pickRestoreDestination();
      if (result.uri != null) {
        // Persist permission for the selected URI
        await KopiaService.instance.persistUriPermission(
          uri: result.uri!,
          read: true,
          write: true,
        );
        state = state.copyWith(destinationUri: result.uri, clearError: true);
      }
    } catch (e) {
      state = state.copyWith(error: 'Failed to pick destination: $e');
    }
  }

  Future<void> startRestore() async {
    if (state.destinationUri == null) {
      state = state.copyWith(error: 'Please select a destination first');
      return;
    }

    state = state.copyWith(
      progress: RestoreProgress(
        state: bridge.RestoreState.preparing,
        totalFiles: 0,
        restoredFiles: 0,
        totalBytes: 0,
        restoredBytes: 0,
      ),
      clearError: true,
    );

    // Cancel any existing subscription
    await _progressSubscription?.cancel();

    // Subscribe to progress stream before starting restore
    _progressSubscription = KopiaService.instance.restoreProgressStream.listen(
      (progress) {
        state = state.copyWith(progress: progress);
      },
      onError: (error) {
        state = state.copyWith(
          progress: RestoreProgress(
            state: bridge.RestoreState.failed,
            totalFiles: state.progress?.totalFiles ?? 0,
            restoredFiles: state.progress?.restoredFiles ?? 0,
            totalBytes: state.progress?.totalBytes ?? 0,
            restoredBytes: state.progress?.restoredBytes ?? 0,
            errorMessage: error.toString(),
          ),
        );
      },
      onDone: () {
        _progressSubscription = null;
      },
    );

    try {
      await KopiaService.instance.startRestore(
        RestoreRequest(
          snapshotId: snapshotId,
          sourcePath: sourcePath,
          destinationUri: state.destinationUri!,
        ),
      );
      // Progress updates come via the stream, no need to set completion here
    } catch (e) {
      await _progressSubscription?.cancel();
      _progressSubscription = null;
      state = state.copyWith(
        progress: RestoreProgress(
          state: bridge.RestoreState.failed,
          totalFiles: 0,
          restoredFiles: 0,
          totalBytes: 0,
          restoredBytes: 0,
          errorMessage: e.toString(),
        ),
      );
    }
  }

  Future<void> cancelRestore() async {
    try {
      await _progressSubscription?.cancel();
      _progressSubscription = null;
      await KopiaService.instance.cancelRestore();
      // The cancelled state will be emitted by the native layer via the stream,
      // but since we cancelled the subscription, set it manually
      state = state.copyWith(
        progress: RestoreProgress(
          state: bridge.RestoreState.cancelled,
          totalFiles: state.progress?.totalFiles ?? 0,
          restoredFiles: state.progress?.restoredFiles ?? 0,
          totalBytes: state.progress?.totalBytes ?? 0,
          restoredBytes: state.progress?.restoredBytes ?? 0,
        ),
      );
    } catch (e) {
      state = state.copyWith(error: 'Failed to cancel: $e');
    }
  }

  void reset() {
    _progressSubscription?.cancel();
    _progressSubscription = null;
    state = state.copyWith(
      progress: null,
      clearError: true,
    );
  }

  @override
  void dispose() {
    _progressSubscription?.cancel();
    super.dispose();
  }
}

/// Provider family for restore state, keyed by snapshot ID and source path.
final restoreProvider = StateNotifierProvider.family<RestoreNotifier, RestoreState,
    ({String snapshotId, String sourcePath})>(
  (ref, params) => RestoreNotifier(
    snapshotId: params.snapshotId,
    sourcePath: params.sourcePath,
  ),
);
