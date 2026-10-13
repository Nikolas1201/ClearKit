package com.clearkit.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer

/**
 * JNI bridge to llama.cpp + mtmd (libllama.so, built from app/src/main/cpp).
 *
 * tryLoad() memory-maps the GGUF text model + vision projector; generate()
 * runs a real vision-language turn on-device (any photo, any question).
 * If the native lib is absent or init/generation fails, callers fall back
 * to the offline OCR engine in LocalAiEngine.
 */
object LlamaBridge {
    var nativeAvailable = false
        private set
    var nativeLoaded = false
        private set

    init {
        try {
            System.loadLibrary("llama")
            nativeAvailable = true
        } catch (_: UnsatisfiedLinkError) {
            nativeAvailable = false
        }
    }

    fun tryLoad(model: File, mmproj: File?): Boolean {
        if (!model.exists()) return false
        if (!nativeAvailable) return true // OCR-fallback session
        return try {
            nativeLoaded = nativeInit(model.absolutePath, mmproj?.absolutePath)
            nativeLoaded
        } catch (_: Throwable) {
            nativeLoaded = false
            false
        }
    }

    /** prompt + optional photo file path. Returns null on any failure (caller falls back). */
    fun generate(prompt: String, imagePath: String?): String? {
        if (!nativeAvailable || !nativeLoaded || prompt.isBlank()) return null
        return try {
            var rgba: ByteArray? = null
            var w = 0
            var h = 0
            if (imagePath != null) {
                decodeRgba(imagePath)?.let { (bytes, width, height) ->
                    rgba = bytes; w = width; h = height
                }
            }
            val out = nativeGenerate(prompt, rgba, w, h, 256)
            out?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** Downscale to max 768px and return raw RGBA bytes (what mtmd_bitmap_init wants, minus alpha). */
    private fun decodeRgba(path: String): Triple<ByteArray, Int, Int>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            // Extra scale-down if still over 768 (sample rounds to powers of 2)
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > 768) {
                val scale = 768f / longest
                val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val buf = ByteBuffer.allocate(bmp.byteCount)
            bmp.copyPixelsToBuffer(buf)
            val bytes = buf.array()
            val res = Triple(bytes, bmp.width, bmp.height)
            bmp.recycle()
            res
        } catch (_: Exception) {
            null
        }
    }

    fun unload() {
        if (nativeAvailable && nativeLoaded) {
            try {
                nativeFree()
            } catch (_: Throwable) { }
        }
        nativeLoaded = false
    }

    @JvmStatic private external fun nativeInit(modelPath: String, mmprojPath: String?): Boolean
    @JvmStatic private external fun nativeGenerate(prompt: String, rgba: ByteArray?, w: Int, h: Int, maxTokens: Int): String?
    @JvmStatic private external fun nativeFree()
}
