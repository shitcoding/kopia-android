package org.kopiaKt.app.ui.screens.filebrowser

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    snapshotId: String,
    initialPath: String,
    onNavigateToPath: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialPath.isEmpty()) "/" else initialPath) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onRestore(initialPath) }) {
                        Icon(Icons.Default.Download, "Restore")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("File browser UI - TODO")
            Text("Snapshot: $snapshotId")
            Text("Path: ${initialPath.ifEmpty { "/" }}")

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onNavigateToPath("$initialPath/subfolder") }) {
                Text("Navigate to subfolder (placeholder)")
            }
        }
    }
}
