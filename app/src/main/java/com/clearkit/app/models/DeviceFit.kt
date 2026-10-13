package com.clearkit.app.models

import android.app.ActivityManager
import android.content.Context

enum class Fit { RECOMMENDED, MAX, NOT_RECOMMENDED }

data class DeviceInfo(val ramBytes: Long, val cores: Int)

fun getDeviceInfo(context: Context): DeviceInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return DeviceInfo(mi.totalMem, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
}

/**
 * Rough on-device fit guide: weights + vision encoder + KV cache need headroom
 * over the raw file size. Three labels, exactly as shown under every model.
 */
fun fitFor(modelBytes: Long, ramBytes: Long): Fit {
    if (ramBytes <= 0 || modelBytes <= 0) return Fit.MAX
    val ratio = modelBytes.toDouble() / ramBytes.toDouble()
    return when {
        ratio <= 0.12 -> Fit.RECOMMENDED
        ratio <= 0.30 -> Fit.MAX
        else -> Fit.NOT_RECOMMENDED
    }
}

fun fitLabel(f: Fit): String = when (f) {
    Fit.RECOMMENDED -> "Recommended"
    Fit.MAX -> "Max for this phone"
    Fit.NOT_RECOMMENDED -> "Not recommended"
}

fun fitWhy(f: Fit, modelBytes: Long, ramBytes: Long): String {
    val pct = if (ramBytes > 0) " (~${(modelBytes * 100 / ramBytes).toInt()}% of RAM)" else ""
    return when (f) {
        Fit.RECOMMENDED -> "Small vs your RAM$pct — should run fine."
        Fit.MAX -> "Heavy for your RAM$pct — expect slowness."
        Fit.NOT_RECOMMENDED -> "Too big for your RAM$pct — likely to fail loading."
    }
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1073741824L) "%.1f GB".format(bytes / 1073741824.0)
    else "${bytes / 1048576} MB"
