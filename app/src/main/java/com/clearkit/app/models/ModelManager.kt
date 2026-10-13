package com.clearkit.app.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class DownloadState(
    val progress: Float = 0f, // 0..1, -1 = indeterminate
    val downloading: Boolean = false,
    val error: String? = null
)

/**
 * Downloads GGUF + mmproj files from Hugging Face into app-private storage,
 * tracks Downloaded state, and gates chat on an explicitly loaded model.
 *
 * Offline: no account, no telemetry. Files live in filesDir/models/<modelId>/.
 */
class ModelManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val _activeModelId = MutableStateFlow<String?>(loadPersistedActive())
    val activeModelId: StateFlow<String?> = _activeModelId

    private val _modelLoaded = MutableStateFlow(false)
    /** True only after Load completes. Chat send is disabled until this is true. */
    val modelLoaded: StateFlow<Boolean> = _modelLoaded

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val json = Json { ignoreUnknownKeys = true }
    private val _customModels = MutableStateFlow<List<VisionModel>>(loadCustom())
    val customModels: StateFlow<List<VisionModel>> = _customModels

    /** Built-in picks plus models added from the Hugging Face browser. */
    fun allModels(): List<VisionModel> = BUILTIN_VISION_MODELS + _customModels.value

    private fun customFile() = File(context.filesDir, "custom_models.json")

    private fun loadCustom(): List<VisionModel> {
        return try {
            val f = customFile()
            if (!f.exists()) emptyList() else json.decodeFromString<List<VisionModel>>(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustom(m: VisionModel) {
        if (_customModels.value.none { it.id == m.id }) {
            _customModels.value = _customModels.value + m
            try {
                customFile().writeText(json.encodeToString(_customModels.value))
            } catch (_: Exception) { }
        }
    }

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }
    fun modelDir(m: VisionModel): File = File(modelsDir(), m.id).apply { mkdirs() }
    fun modelFile(m: VisionModel): File = File(modelDir(m), m.hfFile.substringAfterLast("/"))
    fun mmProjFile(m: VisionModel): File? =
        m.hfMmProj?.let { File(modelDir(m), it.substringAfterLast("/")) }

    fun isDownloaded(m: VisionModel): Boolean {
        val main = modelFile(m)
        if (!main.exists() || main.length() < 10_000_000) return false // ignore partial
        val mm = mmProjFile(m)
        if (m.hfMmProj != null && (mm == null || !mm.exists() || mm.length() < 1_000_000)) return false
        return true
    }

    fun activeModel(): VisionModel? = allModels().firstOrNull { it.id == _activeModelId.value }

    fun selectModel(id: String) {
        _activeModelId.value = id
        // Switching models invalidates the loaded session — user must press Load.
        _modelLoaded.value = false
        context.getSharedPreferences("clearkit", Context.MODE_PRIVATE)
            .edit().putString("active_model", id).apply()
    }

    private fun loadPersistedActive(): String? =
        context.getSharedPreferences("clearkit", Context.MODE_PRIVATE)
            .getString("active_model", null)

    private fun setDl(id: String, s: DownloadState) {
        _downloads.value = _downloads.value + (id to s)
    }

    suspend fun download(m: VisionModel) = withContext(Dispatchers.IO) {
        if (_downloads.value[m.id]?.downloading == true) return@withContext
        setDl(m.id, DownloadState(progress = 0f, downloading = true))
        try {
            downloadOne(m.downloadUrl, modelFile(m), m.id)
            m.mmProjUrl?.let { url ->
                downloadOne(url, mmProjFile(m)!!, m.id)
            }
            setDl(m.id, DownloadState(progress = 1f, downloading = false))
        } catch (e: Exception) {
            setDl(m.id, DownloadState(progress = 0f, downloading = false, error = e.message ?: "download failed"))
        }
    }

    private fun downloadOne(url: String, dest: File, id: String) {
        // Resume support via Range header
        val existing = if (dest.exists()) dest.length() else 0L
        val reqBuilder = Request.Builder().url(url).header("User-Agent", "ClearKit/1.0")
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")
        val req = reqBuilder.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("empty body")
            val total = body.contentLength().let { if (it > 0) it + existing else -1L }
            dest.parentFile?.mkdirs()
            val append = resp.code == 206 && existing > 0
            java.io.FileOutputStream(dest, append).use { fos ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        done += n
                        if (total > 0) setDl(id, DownloadState(progress = done.toFloat() / total, downloading = true))
                        else setDl(id, DownloadState(progress = -1f, downloading = true))
                    }
                    fos.flush()
                }
            }
        }
    }

    /** Load (memory-map) the selected downloaded model. Blocks chat until done. */
    suspend fun loadActiveModel() = withContext(Dispatchers.IO) {
        val m = activeModel() ?: throw IllegalStateException("Pick a model first")
        if (!isDownloaded(m)) throw IllegalStateException("Model not downloaded yet")
        _loading.value = true
        try {
            // llama.cpp backend init happens in LlamaBridge; this call validates
            // files and warms the OCR fallback so first message is instant.
            val ok = com.clearkit.app.ai.LlamaBridge.tryLoad(modelFile(m), mmProjFile(m))
            if (!ok) throw IllegalStateException("Model failed to load (device out of memory?)")
            // Touch files so system keeps them; small delay to surface progress UI.
            kotlinx.coroutines.delay(600)
            _modelLoaded.value = true
        } finally {
            _loading.value = false
        }
    }

    fun unload() {
        com.clearkit.app.ai.LlamaBridge.unload()
        _modelLoaded.value = false
    }

    fun delete(m: VisionModel) {
        modelDir(m).deleteRecursively()
        setDl(m.id, DownloadState())
        if (m.isCustom) {
            _customModels.value = _customModels.value.filterNot { it.id == m.id }
            try {
                customFile().writeText(json.encodeToString(_customModels.value))
            } catch (_: Exception) { }
        }
        if (_activeModelId.value == m.id) _modelLoaded.value = false
    }
}
