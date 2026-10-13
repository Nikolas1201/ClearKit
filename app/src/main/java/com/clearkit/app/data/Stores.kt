package com.clearkit.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class MedItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val detail: String, // dose / time / doctor
    val done: Boolean = false
)

class MedStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file() = File(context.filesDir, "meds.json")

    suspend fun load(): List<MedItem> = withContext(Dispatchers.IO) {
        val f = file()
        if (!f.exists()) return@withContext listOf(
            MedItem(name = "Example: Vitamin D 1000 IU", detail = "1x daily with breakfast"),
            MedItem(name = "Example: Doctor appointment", detail = "Ask reception for next slot")
        )
        try { json.decodeFromString<List<MedItem>>(f.readText()) } catch (_: Exception) { emptyList() }
    }

    suspend fun save(items: List<MedItem>) = withContext(Dispatchers.IO) {
        try { file().writeText(json.encodeToString(items)) } catch (_: Exception) { }
    }
}

@Serializable
data class ProofItem(val id: String = UUID.randomUUID().toString(), val uri: String, val note: String, val timestamp: Long = System.currentTimeMillis())

class ProofStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file() = File(context.filesDir, "proofs.json")

    suspend fun load(): List<ProofItem> = withContext(Dispatchers.IO) {
        val f = file()
        if (!f.exists()) return@withContext emptyList()
        try { json.decodeFromString<List<ProofItem>>(f.readText()) } catch (_: Exception) { emptyList() }
    }

    suspend fun save(items: List<ProofItem>) = withContext(Dispatchers.IO) {
        try { file().writeText(json.encodeToString(items)) } catch (_: Exception) { }
    }
}
