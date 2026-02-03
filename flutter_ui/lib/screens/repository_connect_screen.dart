import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/kopia_bridge.g.dart';
import '../providers/repository_connect_provider.dart';

/// Screen for connecting to a Kopia repository.
/// Supports Local filesystem, S3, WebDAV, and SFTP storage types.
class RepositoryConnectScreen extends ConsumerWidget {
  const RepositoryConnectScreen({
    super.key,
    required this.onConnected,
    required this.onBack,
  });

  final VoidCallback onConnected;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(repositoryConnectProvider);
    final notifier = ref.read(repositoryConnectProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Connect Repository'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onBack,
        ),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Storage type selector
            _StorageTypeSelector(
              selectedType: state.selectedStorageType,
              onTypeSelected: notifier.setStorageType,
            ),
            const SizedBox(height: 24),

            // Storage-specific form
            _buildStorageForm(context, state, notifier),
            const SizedBox(height: 24),

            // Password field
            TextField(
              key: const Key('password_field'),
              onChanged: notifier.setPassword,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(
                labelText: 'Repository Password',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),

            // Remember password checkbox
            Row(
              children: [
                Checkbox(
                  value: state.savePassword,
                  onChanged: (value) => notifier.setSavePassword(value ?? true),
                ),
                const Text('Remember password'),
              ],
            ),

            // Error message
            if (state.error != null) ...[
              const SizedBox(height: 16),
              Text(
                state.error!,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                ),
              ),
            ],
            const SizedBox(height: 24),

            // Connect button
            FilledButton(
              key: const Key('connect_button'),
              onPressed: state.isConnecting
                  ? null
                  : () async {
                      final success = await notifier.connect();
                      if (success) {
                        onConnected();
                      }
                    },
              child: state.isConnecting
                  ? const SizedBox(
                      height: 24,
                      width: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Connect'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStorageForm(
    BuildContext context,
    RepositoryConnectState state,
    RepositoryConnectNotifier notifier,
  ) {
    switch (state.selectedStorageType) {
      case StorageType.localFilesystem:
        return _LocalFilesystemForm(
          path: state.localPath,
          onPathChanged: notifier.setLocalPath,
        );
      case StorageType.s3:
        return _S3Form(
          bucket: state.s3Bucket,
          endpoint: state.s3Endpoint,
          region: state.s3Region,
          accessKeyId: state.s3AccessKeyId,
          onBucketChanged: notifier.setS3Bucket,
          onEndpointChanged: notifier.setS3Endpoint,
          onRegionChanged: notifier.setS3Region,
          onAccessKeyIdChanged: notifier.setS3AccessKeyId,
        );
      case StorageType.webdav:
        return _WebDavForm(
          url: state.webDavUrl,
          username: state.webDavUsername,
          onUrlChanged: notifier.setWebDavUrl,
          onUsernameChanged: notifier.setWebDavUsername,
        );
      case StorageType.sftp:
        return _SftpForm(
          host: state.sftpHost,
          port: state.sftpPort,
          username: state.sftpUsername,
          path: state.sftpPath,
          onHostChanged: notifier.setSftpHost,
          onPortChanged: notifier.setSftpPort,
          onUsernameChanged: notifier.setSftpUsername,
          onPathChanged: notifier.setSftpPath,
        );
      default:
        return Text(
          'Storage type not supported yet',
          style: Theme.of(context).textTheme.bodyMedium,
        );
    }
  }
}

/// Tab selector for storage type
class _StorageTypeSelector extends StatelessWidget {
  const _StorageTypeSelector({
    required this.selectedType,
    required this.onTypeSelected,
  });

  final StorageType selectedType;
  final ValueChanged<StorageType> onTypeSelected;

  static const _supportedTypes = [
    StorageType.localFilesystem,
    StorageType.s3,
    StorageType.webdav,
    StorageType.sftp,
  ];

  String _getLabel(StorageType type) {
    switch (type) {
      case StorageType.localFilesystem:
        return 'Local';
      case StorageType.s3:
        return 'S3';
      case StorageType.webdav:
        return 'WebDAV';
      case StorageType.sftp:
        return 'SFTP';
      default:
        return type.name;
    }
  }

  @override
  Widget build(BuildContext context) {
    final selectedIndex = _supportedTypes.indexOf(selectedType);

    return DefaultTabController(
      length: _supportedTypes.length,
      initialIndex: selectedIndex >= 0 ? selectedIndex : 0,
      child: TabBar(
        isScrollable: true,
        onTap: (index) => onTypeSelected(_supportedTypes[index]),
        tabs: _supportedTypes.map((type) => Tab(text: _getLabel(type))).toList(),
      ),
    );
  }
}

/// Form for local filesystem connection
class _LocalFilesystemForm extends StatelessWidget {
  const _LocalFilesystemForm({
    required this.path,
    required this.onPathChanged,
  });

  final String path;
  final ValueChanged<String> onPathChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextField(
          key: const Key('repo_path_field'),
          controller: TextEditingController(text: path)
            ..selection = TextSelection.collapsed(offset: path.length),
          onChanged: onPathChanged,
          autocorrect: false,
          enableSuggestions: false,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'Repository Path',
            hintText: '/sdcard/kopia_repo',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Enter the full path to the Kopia repository directory on the device.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      ],
    );
  }
}

/// Form for S3 connection
class _S3Form extends StatelessWidget {
  const _S3Form({
    required this.bucket,
    required this.endpoint,
    required this.region,
    required this.accessKeyId,
    required this.onBucketChanged,
    required this.onEndpointChanged,
    required this.onRegionChanged,
    required this.onAccessKeyIdChanged,
  });

  final String bucket;
  final String endpoint;
  final String region;
  final String accessKeyId;
  final ValueChanged<String> onBucketChanged;
  final ValueChanged<String> onEndpointChanged;
  final ValueChanged<String> onRegionChanged;
  final ValueChanged<String> onAccessKeyIdChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TextField(
          controller: TextEditingController(text: bucket)
            ..selection = TextSelection.collapsed(offset: bucket.length),
          onChanged: onBucketChanged,
          decoration: const InputDecoration(
            labelText: 'Bucket',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: endpoint)
            ..selection = TextSelection.collapsed(offset: endpoint.length),
          onChanged: onEndpointChanged,
          decoration: const InputDecoration(
            labelText: 'Endpoint',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: region)
            ..selection = TextSelection.collapsed(offset: region.length),
          onChanged: onRegionChanged,
          decoration: const InputDecoration(
            labelText: 'Region',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: accessKeyId)
            ..selection = TextSelection.collapsed(offset: accessKeyId.length),
          onChanged: onAccessKeyIdChanged,
          decoration: const InputDecoration(
            labelText: 'Access Key ID',
            border: OutlineInputBorder(),
          ),
        ),
      ],
    );
  }
}

/// Form for WebDAV connection
class _WebDavForm extends StatelessWidget {
  const _WebDavForm({
    required this.url,
    required this.username,
    required this.onUrlChanged,
    required this.onUsernameChanged,
  });

  final String url;
  final String username;
  final ValueChanged<String> onUrlChanged;
  final ValueChanged<String> onUsernameChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TextField(
          controller: TextEditingController(text: url)
            ..selection = TextSelection.collapsed(offset: url.length),
          onChanged: onUrlChanged,
          decoration: const InputDecoration(
            labelText: 'WebDAV URL',
            hintText: 'https://example.com/dav/',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: username)
            ..selection = TextSelection.collapsed(offset: username.length),
          onChanged: onUsernameChanged,
          decoration: const InputDecoration(
            labelText: 'Username',
            border: OutlineInputBorder(),
          ),
        ),
      ],
    );
  }
}

/// Form for SFTP connection
class _SftpForm extends StatelessWidget {
  const _SftpForm({
    required this.host,
    required this.port,
    required this.username,
    required this.path,
    required this.onHostChanged,
    required this.onPortChanged,
    required this.onUsernameChanged,
    required this.onPathChanged,
  });

  final String host;
  final int port;
  final String username;
  final String path;
  final ValueChanged<String> onHostChanged;
  final ValueChanged<int> onPortChanged;
  final ValueChanged<String> onUsernameChanged;
  final ValueChanged<String> onPathChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TextField(
          controller: TextEditingController(text: host)
            ..selection = TextSelection.collapsed(offset: host.length),
          onChanged: onHostChanged,
          decoration: const InputDecoration(
            labelText: 'Host',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: port.toString())
            ..selection = TextSelection.collapsed(offset: port.toString().length),
          onChanged: (value) {
            final parsed = int.tryParse(value);
            if (parsed != null) {
              onPortChanged(parsed);
            }
          },
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Port',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: username)
            ..selection = TextSelection.collapsed(offset: username.length),
          onChanged: onUsernameChanged,
          decoration: const InputDecoration(
            labelText: 'Username',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: TextEditingController(text: path)
            ..selection = TextSelection.collapsed(offset: path.length),
          onChanged: onPathChanged,
          decoration: const InputDecoration(
            labelText: 'Path',
            hintText: '/path/to/repository',
            border: OutlineInputBorder(),
          ),
        ),
      ],
    );
  }
}
