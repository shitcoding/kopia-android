import '../bridge/kopia_bridge.g.dart';

/// Service wrapper for the Kopia native bridge.
/// Provides a clean interface for Flutter code to interact with the Kotlin backend.
class KopiaService {
  KopiaService._();

  static final KopiaService instance = KopiaService._();

  final _api = KopiaHostApi();

  /// Pings the native bridge to verify communication.
  /// Returns "pong" if the bridge is working correctly.
  Future<String> ping() async {
    return await _api.ping();
  }

  /// Connects to a Kopia repository.
  Future<RepositoryConnection> connect(ConnectRequest request) async {
    return await _api.connect(request);
  }

  /// Disconnects from the current repository.
  Future<void> disconnect() async {
    await _api.disconnect();
  }

  /// Lists available sources in the connected repository.
  Future<List<SourceInfo?>> listSources() async {
    return await _api.listSources();
  }

  /// Lists snapshots, optionally filtered by source.
  Future<List<SnapshotInfo?>> listSnapshots({SourceInfo? source}) async {
    return await _api.listSnapshots(SnapshotListRequest(source: source));
  }

  /// Gets a specific snapshot by ID.
  Future<SnapshotInfo?> getSnapshot(String snapshotId) async {
    return await _api.getSnapshot(snapshotId);
  }

  /// Lists directory contents at the given path in a snapshot.
  Future<DirectoryPage> listDirectory({
    required String snapshotId,
    required String path,
    int? pageSize,
    String? pageToken,
  }) async {
    return await _api.listDirectory(ListDirectoryRequest(
      snapshotId: snapshotId,
      path: path,
      pageSize: pageSize,
      pageToken: pageToken,
    ));
  }

  /// Starts a restore operation.
  Future<void> startRestore(RestoreRequest request) async {
    await _api.startRestore(request);
  }

  /// Cancels an ongoing restore operation.
  Future<void> cancelRestore() async {
    await _api.cancelRestore();
  }

  /// Opens the SAF picker to select a restore destination.
  Future<SafPickResult> pickRestoreDestination() async {
    return await _api.pickRestoreDestination();
  }

  /// Persists URI permission for the given URI.
  Future<void> persistUriPermission({
    required String uri,
    required bool read,
    required bool write,
  }) async {
    await _api.persistUriPermission(PersistUriPermissionRequest(
      uri: uri,
      read: read,
      write: write,
    ));
  }
}
