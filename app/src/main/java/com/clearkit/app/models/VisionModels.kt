package com.clearkit.app.models

import kotlinx.serialization.Serializable

/**
 * Curated list of phone-friendly open vision-language models.
 * Files are GGUF (llama.cpp) pulled straight from Hugging Face.
 * Keep them small: 0.5–2 GB Q4 quantized.
 */
@Serializable
data class VisionModel(
    val id: String,
    val displayName: String,
    val hfRepo: String,
    val hfFile: String,
    /** mmproj (vision encoder) file for multimodal GGUFs, nullable for bundled models */
    val hfMmProj: String? = null,
    val sizeMbApprox: Int,
    val description: String,
    val isCustom: Boolean = false
) {
    val downloadUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$hfFile"
    val mmProjUrl: String? get() = hfMmProj?.let { "https://huggingface.co/$hfRepo/resolve/main/$it" }
}

val BUILTIN_VISION_MODELS = listOf(
    VisionModel(
        id = "smolvlm-256m-q8",
        displayName = "SmolVLM 256M (Q8) — fastest",
        hfRepo = "ggml-org/SmolVLM-256M-Instruct-GGUF",
        hfFile = "SmolVLM-256M-Instruct-Q8_0.gguf",
        hfMmProj = "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",
        sizeMbApprox = 330,
        description = "Tiny, fast paperwork reader. Best for old phones. Good at forms + receipts."
    ),
    VisionModel(
        id = "moondream2-20250414",
        displayName = "Moondream 2 (F16) — balanced",
        hfRepo = "ggml-org/moondream2-20250414-GGUF",
        hfFile = "moondream2-text-model-f16_ct-vicuna.gguf",
        hfMmProj = "moondream2-mmproj-f16-20250414.gguf",
        sizeMbApprox = 2800,
        description = "Strong small vision model. Great at letters, handwriting, photos of documents. Needs ~4 GB free."
    ),
    VisionModel(
        id = "qwen2-vl-2b-q4",
        displayName = "Qwen2-VL 2B (Q4) — most capable",
        hfRepo = "bartowski/Qwen2-VL-2B-Instruct-GGUF",
        hfFile = "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
        hfMmProj = "mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
        sizeMbApprox = 1700,
        description = "Best understanding, slower + more RAM (~3 GB free needed)."
    )
)
