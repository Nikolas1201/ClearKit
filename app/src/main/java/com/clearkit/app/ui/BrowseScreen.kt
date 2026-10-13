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
import com.clearkit.app.models.HfApi
import com.clearkit.app.models.HfFile
import com.clearkit.app.models.HfModelEntry
import com.clearkit.app.models.ModelManager
import com.clearkit.app.models.VisionModel
import com.clearkit.app.models.fitFor
import com.clearkit.app.models.formatSize
import com.clearkit.app.models.getDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Browse Hugging Face for vision GGUFs. Only repos with vision support
 * (image-text-to-text / multimodal) are listed. Expanding a repo shows its
 * .gguf files with sizes, a hardware fit verdict, and a Download button
 * that adds it to your models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(mm: ModelManager, onBack: () -> Unit, onDownloaded: () -> Unit) {
    val context = LocalContext.current
    val device = remember { getDeviceInfo(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("smolvlm") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<HfModelEntry>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<Map<String, List<HfFile>>>(emptyMap()) }
    var filesLoading by remember { mutableStateOf(false) }
    val downloads by mm.downloads.collectAsState()
    var addedId by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        if (query.isBlank() || searching) return
        searching = true
        error = null
        results = emptyList()
        expanded = null
        scope.launch {
            try {
                val found = HfApi.searchVisionGguf(query)
                // Tag matches can lie: keep only repos that actually ship a vision projector.
                val (sure, verify) = found.partition {
                    it.pipelineTag == "image-text-to-text" || it.pipelineTag == "video-text-to-text"
                }
                val verified = verify.map { entry ->
                    async(Dispatchers.IO) { entry to HfApi.hasVisionProjector(entry.id) }
                }.awaitAll().filter { it.second }.map { it.first }
                results = sure + verified
                if (results.isEmpty()) error = "No vision GGUFs found for \"$query\". Try e.g. qwen2-vl, moondream, llava, minicpm."
            } catch (e: Exception) {
                error = "Search failed: ${e.message} (internet needed for browsing)"
            } finally {
                searching = false
            }
        }
    }

    fun expand(repo: String) {
        if (expanded == repo) {
            expanded = null
            return
        }
        expanded = repo
        if (files.containsKey(repo)) return
        filesLoading = true
        scope.launch {
            try {
                val names = HfApi.listGgufFiles(repo)
                val withSizes = names.map { HfFile(it, HfApi.fileSizeBytes(repo, it)) }
                files = files + (repo to withSizes)
            } catch (e: Exception) {
                files = files + (repo to emptyList())
                error = "Could not list files for $repo: ${e.message}"
            } finally {
                filesLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                title = { Text("Browse Hugging Face") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { DeviceHeader(device.ramBytes, device.cores) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Search vision models") },
                        singleLine = true
                    )
                    Button(onClick = { doSearch() }, enabled = !searching) { Text("Go") }
                }
                Text("Only repos shipping a vision projector (mmproj) are shown.", style = MaterialTheme.typography.labelSmall)
            }
            if (searching) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Searching Hugging Face…")
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(results, key = { it.id }) { entry ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.id, style = MaterialTheme.typography.titleSmall)
                        Text("⬇ ${entry.downloads} · ♥ ${entry.likes}" +
                            (entry.pipelineTag?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall)
                        val repoFiles = files[entry.id]
                        if (expanded == entry.id && repoFiles != null) {
                            if (repoFiles.isEmpty() && !filesLoading) {
                                Text("No .gguf files found in this repo.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                var selected by remember(entry.id) {
                                    mutableStateOf(HfApi.pickMain(repoFiles.map { it.name }))
                                }
                                val mainSize = repoFiles.firstOrNull { it.name == selected }?.sizeBytes
                                val mmproj = HfApi.pickMmproj(repoFiles.map { it.name })
                                val mmSize = repoFiles.firstOrNull { it.name == mmproj }?.sizeBytes
                                val total = (mainSize ?: 0L) + (mmSize ?: 0L)
                                if (total > 0) {
                                    Text("Total download: ${formatSize(total)}", style = MaterialTheme.typography.bodySmall)
                                    FitBadge(fitFor(total, device.ramBytes), total, device.ramBytes)
                                }
                                repoFiles.filterNot { "mmproj" in it.name.lowercase() }.take(12).forEach { f ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = f.name == selected,
                                            onClick = { selected = f.name }
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                if (f.sizeBytes != null) formatSize(f.sizeBytes) else "size unknown",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                if (mmproj != null) {
                                    Text("Vision encoder: $mmproj" +
                                        (mmSize?.let { " (${formatSize(it)})" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("No mmproj projector found — this repo may not work for vision.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                val sel = selected
                                val dl = sel?.let { downloads[HfApi.customId(entry.id, it)] }
                                when {
                                    dl?.downloading == true -> {
                                        if (dl.progress >= 0) LinearProgressIndicator(
                                            progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text("Downloading…", style = MaterialTheme.typography.labelSmall)
                                    }
                                    addedId != null && downloads[addedId]?.downloading == true -> {
                                        Text("Downloading… see Models tab for progress.",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    else -> {
                                        Button(
                                            onClick = {
                                                if (sel == null) return@Button
                                                val quant = HfApi.quantTag(sel)
                                                val repoShort = entry.id.substringAfter("/")
                                                val m = VisionModel(
                                                    id = HfApi.customId(entry.id, sel),
                                                    displayName = "$repoShort $quant (HF)",
                                                    hfRepo = entry.id,
                                                    hfFile = sel,
                                                    hfMmProj = mmproj,
                                                    sizeMbApprox = if (total > 0) (total / 1048576L).toInt() else 0,
                                                    description = "From Hugging Face: ${entry.id}",
                                                    isCustom = true
                                                )
                                                mm.addCustom(m)
                                                mm.selectModel(m.id)
                                                addedId = m.id
                                                scope.launch {
                                                    mm.download(m)
                                                    onDownloaded()
                                                }
                                            },
                                            enabled = sel != null && mmproj != null
                                        ) {
                                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Download")
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { expand(entry.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (expanded == entry.id) "Hide files" else "Show .gguf files")
                        }
                        if (expanded == entry.id && filesLoading && files[entry.id] == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Listing files…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
