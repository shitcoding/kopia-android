import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/kopia_bridge.g.dart';
import '../services/kopia_service.dart';

/// State for the repository connection form
class RepositoryConnectState {
  const RepositoryConnectState({
    this.selectedStorageType = StorageType.localFilesystem,
    this.localPath = '',
    this.s3Bucket = '',
    this.s3Endpoint = 's3.amazonaws.com',
    this.s3Region = 'us-east-1',
    this.s3AccessKeyId = '',
    this.webDavUrl = '',
    this.webDavUsername = '',
    this.sftpHost = '',
    this.sftpPort = 22,
    this.sftpUsername = '',
    this.sftpPath = '',
    this.password = '',
    this.savePassword = true,
    this.isConnecting = false,
    this.error,
  });

  final StorageType selectedStorageType;

  // Local filesystem config
  final String localPath;

  // S3 config
  final String s3Bucket;
  final String s3Endpoint;
  final String s3Region;
  final String s3AccessKeyId;

  // WebDAV config
  final String webDavUrl;
  final String webDavUsername;

  // SFTP config
  final String sftpHost;
  final int sftpPort;
  final String sftpUsername;
  final String sftpPath;

  // Common fields
  final String password;
  final bool savePassword;
  final bool isConnecting;
  final String? error;

  RepositoryConnectState copyWith({
    StorageType? selectedStorageType,
    String? localPath,
    String? s3Bucket,
    String? s3Endpoint,
    String? s3Region,
    String? s3AccessKeyId,
    String? webDavUrl,
    String? webDavUsername,
    String? sftpHost,
    int? sftpPort,
    String? sftpUsername,
    String? sftpPath,
    String? password,
    bool? savePassword,
    bool? isConnecting,
    String? error,
    bool clearError = false,
  }) {
    return RepositoryConnectState(
      selectedStorageType: selectedStorageType ?? this.selectedStorageType,
      localPath: localPath ?? this.localPath,
      s3Bucket: s3Bucket ?? this.s3Bucket,
      s3Endpoint: s3Endpoint ?? this.s3Endpoint,
      s3Region: s3Region ?? this.s3Region,
      s3AccessKeyId: s3AccessKeyId ?? this.s3AccessKeyId,
      webDavUrl: webDavUrl ?? this.webDavUrl,
      webDavUsername: webDavUsername ?? this.webDavUsername,
      sftpHost: sftpHost ?? this.sftpHost,
      sftpPort: sftpPort ?? this.sftpPort,
      sftpUsername: sftpUsername ?? this.sftpUsername,
      sftpPath: sftpPath ?? this.sftpPath,
      password: password ?? this.password,
      savePassword: savePassword ?? this.savePassword,
      isConnecting: isConnecting ?? this.isConnecting,
      error: clearError ? null : (error ?? this.error),
    );
  }

  /// Builds the ConnectionConfig for the current form state
  ConnectionConfig buildConfig() {
    switch (selectedStorageType) {
      case StorageType.localFilesystem:
        return ConnectionConfig(
          storageType: StorageType.localFilesystem,
          local: LocalFilesystemConfig(path: localPath),
        );
      case StorageType.s3:
        return ConnectionConfig(
          storageType: StorageType.s3,
          s3: S3Config(
            bucket: s3Bucket,
            endpoint: s3Endpoint,
            region: s3Region,
            accessKeyId: s3AccessKeyId,
          ),
        );
      case StorageType.webdav:
        return ConnectionConfig(
          storageType: StorageType.webdav,
          webdav: WebDavConfig(
            url: webDavUrl,
            username: webDavUsername,
          ),
        );
      case StorageType.sftp:
        return ConnectionConfig(
          storageType: StorageType.sftp,
          sftp: SftpConfig(
            host: sftpHost,
            port: sftpPort,
            username: sftpUsername,
            path: sftpPath,
          ),
        );
      default:
        throw UnsupportedError('Storage type not supported: $selectedStorageType');
    }
  }
}

/// Notifier for managing repository connection state
class RepositoryConnectNotifier extends StateNotifier<RepositoryConnectState> {
  RepositoryConnectNotifier() : super(const RepositoryConnectState());

  void setStorageType(StorageType type) {
    state = state.copyWith(selectedStorageType: type, clearError: true);
  }

  // Local config
  void setLocalPath(String path) {
    state = state.copyWith(localPath: path);
  }

  // S3 config
  void setS3Bucket(String bucket) {
    state = state.copyWith(s3Bucket: bucket);
  }

  void setS3Endpoint(String endpoint) {
    state = state.copyWith(s3Endpoint: endpoint);
  }

  void setS3Region(String region) {
    state = state.copyWith(s3Region: region);
  }

  void setS3AccessKeyId(String accessKeyId) {
    state = state.copyWith(s3AccessKeyId: accessKeyId);
  }

  // WebDAV config
  void setWebDavUrl(String url) {
    state = state.copyWith(webDavUrl: url);
  }

  void setWebDavUsername(String username) {
    state = state.copyWith(webDavUsername: username);
  }

  // SFTP config
  void setSftpHost(String host) {
    state = state.copyWith(sftpHost: host);
  }

  void setSftpPort(int port) {
    state = state.copyWith(sftpPort: port);
  }

  void setSftpUsername(String username) {
    state = state.copyWith(sftpUsername: username);
  }

  void setSftpPath(String path) {
    state = state.copyWith(sftpPath: path);
  }

  // Common fields
  void setPassword(String password) {
    state = state.copyWith(password: password);
  }

  void setSavePassword(bool save) {
    state = state.copyWith(savePassword: save);
  }

  /// Attempts to connect to the repository
  Future<bool> connect() async {
    state = state.copyWith(isConnecting: true, clearError: true);

    try {
      final config = state.buildConfig();
      final request = ConnectRequest(
        config: config,
        password: state.password,
        savePassword: state.savePassword,
      );

      await KopiaService.instance.connect(request);
      state = state.copyWith(isConnecting: false);
      return true;
    } catch (e) {
      state = state.copyWith(
        isConnecting: false,
        error: e.toString(),
      );
      return false;
    }
  }
}

/// Provider for repository connection state
final repositoryConnectProvider =
    StateNotifierProvider<RepositoryConnectNotifier, RepositoryConnectState>(
  (ref) => RepositoryConnectNotifier(),
);
