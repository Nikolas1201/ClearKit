package com.clearkit.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.clearkit.app.data.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    openDrawer: () -> Unit,
    openModels: () -> Unit,
    onSaveProof: (imageUri: String, note: String) -> Unit
) {
    val chats by vm.chats.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val chat = chats.firstOrNull { it.id == currentId }
    val modelLoaded by vm.models.modelLoaded.collectAsState()
    val loadingModel by vm.models.loading.collectAsState()
    val activeModel = vm.models.activeModelId.collectAsState().value
    val generating by vm.generating.collectAsState()
    val pending by vm.pendingImages.collectAsState()
    val bigText by vm.bigText.collectAsState()
    var input by remember(chat?.id) { mutableStateOf("") }
    var fullscreenUri by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }

    LaunchedEffect(chat?.messages?.size) {
        chat?.messages?.size?.let { if (it > 0) listState.animateScrollToItem(it - 1) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Chats (three lines)")
                    }
                },
                title = { Text(chat?.title ?: "ClearKit", maxLines = 1) },
                actions = {
                    IconButton(onClick = { vm.toggleBigText() }) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Big-text mode")
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (!modelLoaded) {
                    ModelGateBanner(loadingModel, activeModel != null, openModels)
                }
                // Quick helpers
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = { input = "What is this, what do I do by when? " + input }, label = { Text("What is this? By when?") })
                    AssistChip(onClick = { vm.newChat("proof") }, label = { Text("Photo-proof") })
                }
                if (pending.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pending.forEach { uri ->
                            Box {
                                AsyncImage(model = uri, contentDescription = null,
                                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                IconButton(onClick = { vm.removePending(uri) }, Modifier.align(Alignment.TopEnd).size(24.dp)) {
                                    Icon(Icons.Default.Cancel, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach photo")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (modelLoaded) "Describe or just send the photo…" else "Load a model first…") },
                        enabled = !generating,
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = modelLoaded && !generating && !loadingModel && (input.isNotBlank() || pending.isNotEmpty())
                    FilledIconButton(
                        onClick = { vm.send(input.trim()); input = "" },
                        enabled = canSend
                    ) {
                        if (generating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
                if (!modelLoaded) {
                    Text(
                        "Send is disabled until a vision model is loaded.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 8.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    ) { pad ->
        if (chat == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No chats yet — create one from ☰") }
            return@Scaffold
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(chat.messages, key = { it.id }) { msg ->
                MessageBubble(msg, bigText, onSaveProof, onImageClick = { fullscreenUri = it })
            }
            if (generating) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Reading document on-device…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Fullscreen image viewer: tap X (or outside) to return to chat.
    fullscreenUri?.let { uri ->
        Dialog(
            onDismissRequest = { fullscreenUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullscreenUri = null }) {
                AsyncImage(
                    model = Uri.parse(uri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { fullscreenUri = null },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Back to chat",
                        tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelGateBanner(loading: Boolean, hasSelection: Boolean, openModels: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.CloudDownload, contentDescription = null)
            Text(
                if (loading) "Loading vision model…" else if (hasSelection) "Model downloaded — press Load to enable chat." else "No vision model yet. Download one to start (offline).",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = openModels) { Text(if (hasSelection) "Load" else "Models") }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    bigText: Boolean,
    onSaveProof: (String, String) -> Unit,
    onImageClick: (String) -> Unit
) {
    val isUser = msg.role == "user"
    val bodyStyle = if (bigText) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(12.dp).widthIn(max = 340.dp)) {
                msg.imageUris.forEach { u ->
                    AsyncImage(model = Uri.parse(u), contentDescription = "Tap to enlarge",
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { onImageClick(u) },
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(6.dp))
                }
                if (msg.text.isNotBlank()) Text(msg.text, style = bodyStyle)
            }
        }
        if (!isUser && msg.imageUris.isNotEmpty()) {
            TextButton(onClick = { onSaveProof(msg.imageUris.first(), msg.text.take(120)) }) {
                Text("Save as photo-proof")
            }
        }
    }
}
