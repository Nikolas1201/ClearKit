package com.clearkit.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "user", // user | assistant
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val mode: String = "general", // general | scan | med | proof
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

class ChatStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private fun file() = File(context.filesDir, "chats.json")

    suspend fun load(): List<Chat> = withContext(Dispatchers.IO) {
        val f = file()
        if (!f.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<Chat>>(f.readText())
        } catch (_: Exception) { emptyList() }
    }

    suspend fun save(chats: List<Chat>) = withContext(Dispatchers.IO) {
        try { file().writeText(json.encodeToString(chats)) } catch (_: Exception) { }
    }

    /** Copy a picked image into private cache and return its Uri string (survives reboot). */
    suspend fun persistImage(src: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(src)?.use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        }
        Uri.fromFile(dest).toString()
    }
}
