package org.kopiaKt.app.ui.screens.repositoryconnect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.kopiaKt.app.domain.model.StorageType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryConnectScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RepositoryConnectViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Repository") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Storage type tabs
            val storageTypes = listOf(StorageType.LOCAL_FILESYSTEM, StorageType.S3, StorageType.WEBDAV, StorageType.SFTP)
            val selectedIndex = storageTypes.indexOf(uiState.selectedStorageType).coerceAtLeast(0)

            ScrollableTabRow(selectedTabIndex = selectedIndex) {
                storageTypes.forEachIndexed { index, type ->
                    Tab(
                        selected = selectedIndex == index,
                        onClick = { viewModel.updateStorageType(type) },
                        text = { Text(if (type == StorageType.LOCAL_FILESYSTEM) "Local" else type.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Storage-specific form fields
            when (uiState.selectedStorageType) {
                StorageType.LOCAL_FILESYSTEM -> LocalForm(
                    state = uiState.localConfig,
                    onUpdate = viewModel::updateLocalConfig
                )
                StorageType.S3 -> S3Form(
                    state = uiState.s3Config,
                    onUpdate = viewModel::updateS3Config
                )
                StorageType.WEBDAV -> WebDavForm(
                    state = uiState.webDavConfig,
                    onUpdate = viewModel::updateWebDavConfig
                )
                StorageType.SFTP -> SftpForm(
                    state = uiState.sftpConfig,
                    onUpdate = viewModel::updateSftpConfig
                )
                else -> {
                    Text("Storage type not supported yet")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Password field
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Repository Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("password_field"),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Remember password checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = uiState.savePassword,
                    onCheckedChange = viewModel::updateSavePassword
                )
                Text("Remember password")
            }

            // Error message
            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connect button
            Button(
                onClick = { viewModel.connect(onConnected) },
                enabled = !uiState.isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_button")
            ) {
                if (uiState.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun LocalForm(
    state: LocalFormState,
    onUpdate: (path: String) -> Unit
) {
    OutlinedTextField(
        value = state.path,
        onValueChange = onUpdate,
        label = { Text("Repository Path") },
        placeholder = { Text("/sdcard/kopia_repo") },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("repo_path_field"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Uri
        )
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Enter the full path to the Kopia repository directory on the device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun S3Form(
    state: S3FormState,
    onUpdate: (bucket: String, endpoint: String, region: String, accessKeyId: String) -> Unit
) {
    OutlinedTextField(
        value = state.bucket,
        onValueChange = { onUpdate(it, state.endpoint, state.region, state.accessKeyId) },
        label = { Text("Bucket") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.endpoint,
        onValueChange = { onUpdate(state.bucket, it, state.region, state.accessKeyId) },
        label = { Text("Endpoint") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.region,
        onValueChange = { onUpdate(state.bucket, state.endpoint, it, state.accessKeyId) },
        label = { Text("Region") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.accessKeyId,
        onValueChange = { onUpdate(state.bucket, state.endpoint, state.region, it) },
        label = { Text("Access Key ID") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun WebDavForm(
    state: WebDavFormState,
    onUpdate: (url: String, username: String) -> Unit
) {
    OutlinedTextField(
        value = state.url,
        onValueChange = { onUpdate(it, state.username) },
        label = { Text("WebDAV URL") },
        placeholder = { Text("https://example.com/dav/") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = { onUpdate(state.url, it) },
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun SftpForm(
    state: SftpFormState,
    onUpdate: (host: String, port: Int, username: String, path: String) -> Unit
) {
    OutlinedTextField(
        value = state.host,
        onValueChange = { onUpdate(it, state.port, state.username, state.path) },
        label = { Text("Host") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.port.toString(),
        onValueChange = { onUpdate(state.host, it.toIntOrNull() ?: 22, state.username, state.path) },
        label = { Text("Port") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = { onUpdate(state.host, state.port, it, state.path) },
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = state.path,
        onValueChange = { onUpdate(state.host, state.port, state.username, it) },
        label = { Text("Path") },
        placeholder = { Text("/path/to/repository") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
