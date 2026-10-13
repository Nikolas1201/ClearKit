package com.clearkit.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clearkit.app.ClearKitApp
import com.clearkit.app.data.Chat
import com.clearkit.app.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val app: ClearKitApp) : ViewModel() {
    private val store get() = app.chatStore
    private val engine get() = app.aiEngine
    val models get() = app.modelManager

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId

    private val _pendingImages = MutableStateFlow<List<Uri>>(emptyList())
    val pendingImages: StateFlow<List<Uri>> = _pendingImages

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _bigText = MutableStateFlow(false)
    val bigText: StateFlow<Boolean> = _bigText

    fun currentChat(): Chat? = _chats.value.firstOrNull { it.id == _currentId.value }

    init {
        viewModelScope.launch {
            val loaded = store.load()
            if (loaded.isEmpty()) {
                val c = Chat(title = "Welcome", messages = listOf(
                    ChatMessage(role = "assistant", text = "Welcome to ClearKit — your offline paperwork helper.\n\n1. Open ☰ → Models and download a vision model, then press Load.\n2. Send a photo of any letter, prescription, or receipt.\n3. I'll tell you what it is, what to do, and by when — all on-device.")
                ))
                _chats.value = listOf(c)
                _currentId.value = c.id
                store.save(_chats.value)
            } else {
                _chats.value = loaded.sortedByDescending { it.updatedAt }
                _currentId.value = loaded.maxByOrNull { it.updatedAt }?.id
            }
        }
    }

    fun toggleBigText() { _bigText.value = !_bigText.value }

    fun newChat(mode: String = "general") {
        viewModelScope.launch {
            val title = when (mode) {
                "scan" -> "Scan a letter"
                "med" -> "Meds & appointments"
                "proof" -> "Photo-proof"
                else -> "New chat"
            }
            val seed = when (mode) {
                "scan" -> "Send a photo of the letter/form. I'll reply: what it is, what to do, by when."
                "med" -> "Send a photo of the prescription or appointment card (big-text mode available in ☰)."
                "proof" -> "Send a photo of the room/item/receipt. I'll log it as timestamped proof."
                else -> null
            }
            val c = Chat(
                title = title, mode = mode,
                messages = seed?.let { listOf(ChatMessage(role = "assistant", text = it)) } ?: emptyList()
            )
            _chats.value = listOf(c) + _chats.value
            _currentId.value = c.id
            _pendingImages.value = emptyList()
            store.save(_chats.value)
        }
    }

    fun selectChat(id: String) {
        _currentId.value = id
        _pendingImages.value = emptyList()
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            _chats.value = _chats.value.filterNot { it.id == id }
            if (_currentId.value == id) _currentId.value = _chats.value.firstOrNull()?.id
            if (_chats.value.isEmpty()) newChat()
            else store.save(_chats.value)
        }
    }

    fun addImages(uris: List<Uri>) {
        _pendingImages.value = (_pendingImages.value + uris).take(4)
    }

    fun removePending(uri: Uri) {
        _pendingImages.value = _pendingImages.value - uri
    }

    fun clearPending() { _pendingImages.value = emptyList() }

    /** Send is gated by the caller on models.modelLoaded == true. */
    fun send(text: String) {
        val chat = currentChat() ?: return
        if (_generating.value) return
        val attached = _pendingImages.value
        if (text.isBlank() && attached.isEmpty()) return
        // Instant UI: bubble appears and photo leaves the box before any heavy work.
        _pendingImages.value = emptyList()
        val userMsg = ChatMessage(role = "user", text = text, imageUris = attached.map { it.toString() })
        val base = chat.copy(
            messages = chat.messages + userMsg,
            title = chat.messages.filter { it.role == "user" }.firstOrNull()?.text?.take(32)
                ?: text.take(32).ifBlank { if (attached.isNotEmpty()) "Photo chat" else chat.title },
            updatedAt = System.currentTimeMillis()
        )
        _chats.value = _chats.value.map { if (it.id == chat.id) base else it }
        _generating.value = true
        // Heavy work (file copies, model inference) OFF the main thread — never freeze the app.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Persist images so history survives reboot, then point the bubble at the copies.
                val persisted = attached.map { runCatching { Uri.parse(store.persistImage(it)) }.getOrNull() ?: it }
                val swapped = base.copy(
                    messages = base.messages.dropLast(1) +
                        userMsg.copy(imageUris = persisted.map { it.toString() })
                )
                // Follow-ups without a new photo reuse the latest photo in this chat,
                // so the vision model still sees what the question is about.
                val priorImage = swapped.messages.asReversed()
                    .flatMap { it.imageUris }.firstOrNull()?.let { Uri.parse(it) }
                val modelImages = if (persisted.isNotEmpty()) persisted else listOfNotNull(priorImage)
                val reply = engine.answer(text, modelImages, chat.mode, chat.id)
                val updated = swapped.copy(
                    messages = swapped.messages + ChatMessage(role = "assistant", text = reply))
                _chats.value = _chats.value.map { if (it.id == chat.id) updated else it }
                store.save(_chats.value)
            } catch (e: Exception) {
                val current = _chats.value.firstOrNull { it.id == chat.id } ?: base
                val err = current.copy(
                    messages = current.messages +
                        ChatMessage(role = "assistant", text = "Something went wrong offline: ${e.message}"))
                _chats.value = _chats.value.map { if (it.id == chat.id) err else it }
            } finally {
                _generating.value = false
            }
        }
    }
}
