package com.clearkit.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clearkit.app.data.Chat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    chats: List<Chat>,
    currentId: String?,
    modelName: String?,
    modelLoaded: Boolean,
    bigText: Boolean,
    onSelectChat: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenMeds: () -> Unit,
    onOpenProofs: () -> Unit,
    onToggleBigText: () -> Unit
) {
    ModalDrawerSheet {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ClearKit", style = MaterialTheme.typography.headlineSmall)
            Text("Offline life-paperwork helper", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onNewChat("general") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New chat")
            }
            // Model status card
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Vision model", style = MaterialTheme.typography.labelMedium)
                    Text(modelName ?: "None selected", style = MaterialTheme.typography.bodyMedium)
                    Text(if (modelLoaded) "● Loaded — chat enabled" else "○ Not loaded — chat disabled",
                        color = if (modelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenModels) { Text("Manage / download models") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onToggleBigText() }.padding(vertical = 4.dp)) {
                Checkbox(checked = bigText, onCheckedChange = { onToggleBigText() })
                Text("Big-text mode")
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Helpers", style = MaterialTheme.typography.labelMedium)
            NavigationDrawerItem(label = { Text("Scan letter / form") }, selected = false,
                icon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = { onNewChat("scan") })
            NavigationDrawerItem(label = { Text("Meds & appointments") }, selected = false,
                icon = { Icon(Icons.Default.Medication, null) }, onClick = onOpenMeds)
            NavigationDrawerItem(label = { Text("Photo-proof") }, selected = false,
                icon = { Icon(Icons.Default.CameraAlt, null) }, onClick = onOpenProofs)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Chats (${chats.size})", style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(chats, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = { Text(c.title.ifBlank { "Chat" }, maxLines = 1) },
                    selected = c.id == currentId,
                    onClick = { onSelectChat(c.id) },
                    badge = {
                        IconButton(onClick = { onDeleteChat(c.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete chat", modifier = Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedsScreen(vm: MedsViewModel, bigText: Boolean, onBack: () -> Unit) {
    val items by vm.items.collectAsState()
    var name by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    val style = if (bigText) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium
    Scaffold(topBar = {
        CenterAlignedTopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Meds & appointments") })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Medicine / appointment") })
            OutlinedTextField(detail, { detail = it }, Modifier.fillMaxWidth(), label = { Text("Dose / time / doctor") })
            Button(onClick = { if (name.isNotBlank()) { vm.add(name.trim(), detail.trim()); name = ""; detail = "" } },
                modifier = Modifier.fillMaxWidth()) { Text("Add") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { m ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(m.done, { vm.toggle(m.id) })
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = style)
                                if (m.detail.isNotBlank()) Text(m.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.remove(m.id) }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofsScreen(items: com.clearkit.app.data.ProofItemList, onBack: () -> Unit, onDelete: (String) -> Unit) {
    Scaffold(topBar = {
        CenterAlignedTopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Photo-proof") })
    }) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No proofs yet.\nSend a photo in chat → Save as photo-proof.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { p ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AsyncImage(p.uri, null, Modifier.fillMaxWidth())
                            Text(p.note.ifBlank { "Proof" }, style = MaterialTheme.typography.bodyMedium)
                            val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                            Text(fmt.format(Date(p.timestamp)), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onDelete(p.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
