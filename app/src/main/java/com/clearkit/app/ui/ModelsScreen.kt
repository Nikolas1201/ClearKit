package com.clearkit.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clearkit.app.models.ModelManager
import com.clearkit.app.models.fitFor
import com.clearkit.app.models.getDeviceInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(mm: ModelManager, onBack: () -> Unit, onBrowse: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val device = remember { getDeviceInfo(context) }
    val downloads by mm.downloads.collectAsState()
    val activeId by mm.activeModelId.collectAsState()
    val loaded by mm.modelLoaded.collectAsState()
    val loading by mm.loading.collectAsState()
    val customs by mm.customModels.collectAsState()
    val models = remember(customs) { mm.allModels() }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Recompose download states as files change
    var tick by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                title = { Text("Vision models (offline)") }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Models download from Hugging Face once, then run 100% offline. Pick one, download it, then press Load — chat stays disabled until loaded.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item { DeviceHeader(device.ramBytes, device.cores) }
            item {
                OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse more vision models on Hugging Face")
                }
            }
            items(models, key = { it.id }) { m ->
                val dl = downloads[m.id]
                val downloaded = remember(tick, customs) { mm.isDownloaded(m) }
                val isActive = activeId == m.id
                val sizeBytes = m.sizeMbApprox * 1048576L
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (downloaded) AssistChip(onClick = {}, label = { Text("Downloaded") },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) })
                        }
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                        Text("~${m.sizeMbApprox} MB • ${m.hfRepo}", style = MaterialTheme.typography.labelSmall)
                        if (sizeBytes > 0) FitBadge(fitFor(sizeBytes, device.ramBytes), sizeBytes, device.ramBytes)
                        if (dl?.downloading == true) {
                            if (dl.progress >= 0) LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(if (dl.progress >= 0) "${(dl.progress * 100).toInt()}%" else "Downloading…",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        dl?.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            when {
                                dl?.downloading == true -> {
                                    OutlinedButton(onClick = {}, enabled = false) { Text("Downloading…") }
                                }
                                !downloaded -> {
                                    Button(onClick = { scope.launch { mm.download(m); tick++ } }) {
                                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("Download")
                                    }
                                }
                                else -> {
                                    if (isActive && loaded) {
                                        AssistChip(onClick = {}, label = { Text("Active ✓") })
                                        OutlinedButton(onClick = { mm.unload() }) { Text("Unload") }
                                    } else {
                                        Button(
                                            onClick = { scope.launch {
                                                mm.selectModel(m.id)
                                                loadError = runCatching { mm.loadActiveModel() }
                                                    .exceptionOrNull()?.message
                                            } },
                                            enabled = !loading
                                        ) {
                                            if (loading && isActive) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            else Text(if (isActive) "Load" else "Switch + Load")
                                        }
                                    }
                                    TextButton(onClick = { confirmDelete = m.id }) { Text("Delete") }
                                }
                            }
                        }
                        if (isActive && !loaded && downloaded) {
                            Text("Selected — press Load to enable chat.", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                loadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Tip: SmolVLM for old phones, Moondream 2 for most people, Qwen2-VL for best reading (needs ~3 GB free RAM). Wi-Fi recommended.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirmDelete?.let { id ->
        val m = models.firstOrNull { it.id == id } ?: return@let
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { mm.delete(m); tick++; confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Delete ${m.displayName}?") },
            text = { Text("Frees ~${m.sizeMbApprox} MB. You can re-download anytime.") }
        )
    }
}
