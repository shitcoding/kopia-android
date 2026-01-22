package org.kopiaKt.app.ui.screens.restore

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RestoreScreen(
    snapshotId: String,
    sourcePath: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Restore", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text("From: $sourcePath")

        Spacer(modifier = Modifier.height(32.dp))

        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))

        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            Button(onClick = onComplete) {
                Text("Done (placeholder)")
            }
        }
    }
}
