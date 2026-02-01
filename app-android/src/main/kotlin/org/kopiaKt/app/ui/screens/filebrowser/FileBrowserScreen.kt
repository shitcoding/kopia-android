package org.kopiaKt.app.ui.screens.filebrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    snapshotId: String,
    initialPath: String,
    onNavigateToPath: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(initialPath) {
        viewModel.loadDirectory(initialPath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (initialPath.isEmpty()) "/" else initialPath.split("/").lastOrNull() ?: "/",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onRestore(initialPath) },
                        modifier = Modifier.testTag("restore_button")
                    ) {
                        Icon(Icons.Default.Download, "Restore this folder")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Breadcrumb navigation
            BreadcrumbRow(
                pathHistory = uiState.pathHistory,
                onNavigate = { path ->
                    if (path != uiState.currentPath) {
                        onNavigateToPath(path)
                    }
                }
            )

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    uiState.error != null -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = uiState.error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadDirectory(initialPath) }) {
                                Text("Retry")
                            }
                        }
                    }
                    uiState.entries.isEmpty() -> {
                        Text(
                            text = "Empty folder",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.entries, key = { it.name }) { entry ->
                                FileEntryItem(
                                    entry = entry,
                                    onClick = {
                                        when (entry.type) {
                                            FileEntryType.DIRECTORY -> {
                                                val newPath = if (uiState.currentPath.isEmpty()) {
                                                    entry.name
                                                } else {
                                                    "${uiState.currentPath}/${entry.name}"
                                                }
                                                onNavigateToPath(newPath)
                                            }
                                            else -> {
                                                // Could show file details or restore single file
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(
    pathHistory: List<PathSegment>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pathHistory.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            TextButton(
                onClick = { onNavigate(segment.fullPath) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == pathHistory.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun FileEntryItem(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier
            .testTag("file_entry_${entry.name}")
            .clickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = getIconForEntry(entry),
                contentDescription = null,
                tint = getIconColor(entry)
            )
        },
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row {
                if (entry.type == FileEntryType.FILE) {
                    Text(
                        text = formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                entry.modTime?.let { time ->
                    Text(
                        text = formatDateTime(time),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        trailingContent = {
            if (entry.type == FileEntryType.DIRECTORY) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Navigate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun getIconForEntry(entry: FileEntry): ImageVector {
    return when (entry.type) {
        FileEntryType.DIRECTORY -> Icons.Default.Folder
        FileEntryType.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
        FileEntryType.SYMLINK -> Icons.Default.Link
        FileEntryType.UNKNOWN -> Icons.Default.HelpOutline
    }
}

@Composable
private fun getIconColor(entry: FileEntry): androidx.compose.ui.graphics.Color {
    return when (entry.type) {
        FileEntryType.DIRECTORY -> MaterialTheme.colorScheme.primary
        FileEntryType.SYMLINK -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatDateTime(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
