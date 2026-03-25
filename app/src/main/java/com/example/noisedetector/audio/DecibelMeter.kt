package com.example.noisedetector.audio

import kotlin.math.log10
import kotlin.math.sqrt

object DecibelMeter {

    /**
     * Uncalibrated display level (0–120) derived from 16-bit PCM RMS.
     * Comparable across sessions on the same device; not absolute SPL.
     */
    fun rmsToDisplayDb(samples: ShortArray, readCount: Int): Float {
        if (readCount <= 0) return 0f
        var sum = 0.0
        for (i in 0 until readCount) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / readCount)
        if (rms < 1.0) return 0f
        val normalized = rms / 32768.0
        val db = 20.0 * log10(normalized) + 96.0
        return db.toFloat().coerceIn(0f, 120f)
    }
}
