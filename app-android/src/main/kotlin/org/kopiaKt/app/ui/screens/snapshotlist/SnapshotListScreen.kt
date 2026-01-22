package org.kopiaKt.app.ui.screens.snapshotlist

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotListScreen(
    onSnapshotSelected: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snapshots") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
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
            Text("Snapshot list UI - TODO")

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onSnapshotSelected("test-snapshot-id") }) {
                Text("Browse snapshot (placeholder)")
            }
        }
    }
}
