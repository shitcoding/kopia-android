import 'package:pigeon/pigeon.dart';

// Pigeon configuration - run: dart run pigeon --input pigeons/kopia_bridge.dart
@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/bridge/kopia_bridge.g.dart',
  kotlinOut: '../app-android/src/main/kotlin/org/kopiaKt/app/bridge/KopiaBridge.g.kt',
  kotlinOptions: KotlinOptions(
    package: 'org.kopiaKt.app.bridge',
  ),
  dartPackageName: 'flutter_ui',
))

// ===== Enums =====

enum StorageType { localFilesystem, s3, webdav, sftp, saf }

enum FileEntryType { file, directory, symlink, unknown }

enum RestoreState { idle, preparing, inProgress, completed, failed, cancelled }

// ===== Domain Models =====

class SourceInfo {
  late String host;
  late String userName;
  late String path;
}

class SnapshotStats {
  late int totalFileSize;
  late int totalFileCount;
  late int totalDirectoryCount;
}

class SnapshotInfo {
  late String id;
  late SourceInfo source;
  late int startTimeEpochMs;
  int? endTimeEpochMs;
  late String description;
  SnapshotStats? stats;
  late bool isIncomplete;
  late Map<String?, String?> tags;
}

class FileEntry {
  late String name;
  late FileEntryType type;
  late int size;
  int? modTimeEpochMs;
  late int permissions;
  String? objectId;
}

class RestoreProgress {
  late RestoreState state;
  late int totalFiles;
  late int restoredFiles;
  late int totalBytes;
  late int restoredBytes;
  String? currentFile;
  String? errorMessage;
}

// ===== Connection Config (per storage type) =====

class LocalFilesystemConfig {
  late String path;
}

class S3Config {
  late String bucket;
  late String endpoint;
  late String region;
  late String accessKeyId;
}

class WebDavConfig {
  late String url;
  late String username;
}

class SftpConfig {
  late String host;
  late int port;
  late String username;
  late String path;
}

class SafConfig {
  late String treeUri;
  late String displayPath;
}

class ConnectionConfig {
  late StorageType storageType;
  LocalFilesystemConfig? local;
  S3Config? s3;
  WebDavConfig? webdav;
  SftpConfig? sftp;
  SafConfig? saf;
}

class RepositoryConnection {
  late String id;
  late String displayName;
  late StorageType storageType;
  late ConnectionConfig connectionConfig;
  int? lastConnectedEpochMs;
  late bool isConnected;
}

class RestoreOptions {
  late int parallel;
  late bool incremental;
  late bool overwriteExisting;
}

// ===== Request/Response Objects =====

class ConnectRequest {
  late ConnectionConfig config;
  late String password;
  late bool savePassword;
}

class SnapshotListRequest {
  SourceInfo? source;
}

class ListDirectoryRequest {
  late String snapshotId;
  late String path;
  int? pageSize;
  String? pageToken;
}

class DirectoryPage {
  late List<FileEntry?> entries;
  String? nextPageToken;
}

class RestoreRequest {
  late String snapshotId;
  late String sourcePath;
  late String destinationUri;
  RestoreOptions? options;
}

class SafPickResult {
  String? uri;
  String? displayName;
}

class PersistUriPermissionRequest {
  late String uri;
  late bool read;
  late bool write;
}

// ===== Host API (Flutter calls Kotlin) =====

@HostApi()
abstract class KopiaHostApi {
  /// Simple ping method to verify bridge communication.
  /// Returns "pong" if the bridge is working correctly.
  @async
  String ping();

  @async
  RepositoryConnection connect(ConnectRequest request);

  @async
  void disconnect();

  @async
  List<SourceInfo?> listSources();

  @async
  List<SnapshotInfo?> listSnapshots(SnapshotListRequest request);

  @async
  SnapshotInfo? getSnapshot(String snapshotId);

  @async
  DirectoryPage listDirectory(ListDirectoryRequest request);

  @async
  void startRestore(RestoreRequest request);

  @async
  void cancelRestore();

  @async
  SafPickResult pickRestoreDestination();

  @async
  void persistUriPermission(PersistUriPermissionRequest request);
}

// Note: Event channels for streaming restore progress are handled
// separately as Pigeon 25.x EventChannelApi has limited support.
// We'll use manual EventChannel setup for RestoreProgressStream.
