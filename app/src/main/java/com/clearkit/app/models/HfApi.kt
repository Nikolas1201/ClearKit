package com.clearkit.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HfModelEntry(
    val id: String,
    val downloads: Int,
    val likes: Int,
    val pipelineTag: String?,
    val tags: List<String>
)

data class HfFile(val name: String, val sizeBytes: Long?)

/**
 * Minimal Hugging Face Hub client (public API, no login).
 * Only surfaces GGUF repos with vision support.
 */
object HfApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "ClearKit/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw RuntimeException("empty response")
        }
    }

    suspend fun searchVisionGguf(query: String): List<HfModelEntry> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=30"
        val arr = JSONArray(get(url))
        val out = mutableListOf<HfModelEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            if (o.optBoolean("gated", false) || o.optBoolean("private", false) || o.optBoolean("disabled", false)) continue
            val pipe = o.optString("pipeline_tag", null).takeIf { it != null && it != "null" }
            val tags = mutableListOf<String>()
            val ta = o.optJSONArray("tags")
            if (ta != null) for (j in 0 until ta.length()) tags += ta.optString(j)
            if (!isVision(tags, pipe)) continue
            out += HfModelEntry(id, o.optInt("downloads"), o.optInt("likes"), pipe, tags)
        }
        out
    }

    fun isVision(tags: List<String>, pipe: String?): Boolean {
        if (pipe == "image-text-to-text" || pipe == "video-text-to-text") return true
        if (pipe == "automatic-speech-recognition" || pipe == "text-to-speech" || pipe == "audio-to-audio") return false
        val t = tags.map { it.lowercase() }
        return "multimodal" in t || "vision" in t || "image-text-to-text" in t
    }

    /** Second verification layer: the repo must actually ship a vision projector file. */
    suspend fun hasVisionProjector(repo: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(get("https://huggingface.co/api/models/$repo"))
            val sib = o.optJSONArray("siblings") ?: return@withContext false
            for (i in 0 until sib.length()) {
                if ("mmproj" in sib.getJSONObject(i).optString("rfilename").lowercase()) {
                    return@withContext true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun listGgufFiles(repo: String): List<String> = withContext(Dispatchers.IO) {
        val o = JSONObject(get("https://huggingface.co/api/models/$repo"))
        val sib = o.optJSONArray("siblings") ?: return@withContext emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until sib.length()) {
            val n = sib.getJSONObject(i).optString("rfilename")
            if (n.endsWith(".gguf", ignoreCase = true)) out += n
        }
        out.sorted()
    }

    suspend fun fileSizeBytes(repo: String, file: String): Long? = withContext(Dispatchers.IO) {
        try {
            val url = "https://huggingface.co/$repo/resolve/main/$file"
            val req = Request.Builder().url(url).head().header("User-Agent", "ClearKit/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.header("Content-Length")?.toLongOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Prefer a balanced phone-friendly quant for the default pick. */
    fun pickMain(files: List<String>): String? {
        val mains = files.filterNot { "mmproj" in it.lowercase() }
        if (mains.isEmpty()) return null
        val pref = listOf("q4_k_m", "q4_k_s", "q4_0", "q5_k_m", "q4_k", "iq4_xs", "q3_k_m", "q8_0", "f16")
        for (p in pref) {
            mains.firstOrNull { p in it.lowercase() }?.let { return it }
        }
        return mains.first()
    }

    fun pickMmproj(files: List<String>): String? {
        val cands = files.filter { "mmproj" in it.lowercase() }
        if (cands.isEmpty()) return null
        return cands.firstOrNull { "q8_0" in it.lowercase() }
            ?: cands.firstOrNull { "f16" in it.lowercase() }
            ?: cands.first()
    }

    fun quantTag(file: String): String {
        val m = Regex("""(iq?\d[\w]*|f16|f32)""", RegexOption.IGNORE_CASE).find(file)
        return m?.value?.uppercase() ?: "GGUF"
    }

    fun customId(repo: String, file: String): String {
        val safe = (repo.replace("/", "__") + "__" + file.substringBeforeLast("."))
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
        return "hf-$safe"
    }
}
