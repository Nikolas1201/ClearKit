package com.clearkit.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clearkit.app.models.Fit
import com.clearkit.app.models.fitLabel
import com.clearkit.app.models.fitWhy

/** The three fit verdicts, shown under every model. */
@Composable
fun FitBadge(fit: Fit, modelBytes: Long, ramBytes: Long) {
    val (container, content, icon) = when (fit) {
        Fit.RECOMMENDED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer, Icons.Default.CheckCircle)
        Fit.MAX -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer, Icons.Default.Warning)
        Fit.NOT_RECOMMENDED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer, Icons.Default.Block)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AssistChip(
            onClick = {},
            label = { Text(fitLabel(fit)) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = container, labelColor = content)
        )
        Text(fitWhy(fit, modelBytes, ramBytes), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun DeviceHeader(ramBytes: Long, cores: Int) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Smartphone, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("This phone", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${"%.1f".format(ramBytes / 1073741824.0)} GB RAM · $cores cores",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Fit labels below are scanned against this hardware.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
