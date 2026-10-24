package com.robotuth.v3.stt.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pipeline xử lý âm thanh cho CTC ASR.
 * Tất cả hàm là pure functions, không có Android dependency.
 *
 * VAD phải chạy trên **raw hoặc chỉ DC-removal** — không chạy sau peak-normalize
 * (normalize khuếch đại ồn nhỏ → peak >= 500 luôn true).
 */
object AudioPipeline {

    // Hằng số DSP — VAD trên raw PCM 16-bit
    private const val PRE_EMPHASIS_ALPHA = 0.97f
    private const val VAD_RMS_THRESHOLD = 80.0
    private const val VAD_SPEECH_FRAME_RATIO = 0.08f
    private const val VAD_PEAK_THRESHOLD = 500
    private const val NORMALIZE_TARGET = 0.85f
    private const val SAMPLE_RATE = 16000

    /**
     * Pipeline cũ (A/B): DC removal → pre-emphasis → peak normalize.
     * Mặc định **không** dùng cho ASR; bật [USE_LEGACY_PROCESS] khi so sánh WER.
     */
    fun process(input: ShortArray): ShortArray =
        normalize(applyPreEmphasis(removeDcOffset(input)))

    /** Chỉ bỏ DC offset — an toàn, không amplify. */
    fun removeDcOffset(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val mean = samples.map { it.toLong() }.average()
        return ShortArray(samples.size) { i ->
            (samples[i] - mean).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * VAD gate: true nếu đủ frame có năng lượng.
     * Gọi trên **raw** hoặc **DC-only**, không gọi sau [process]/normalize.
     */
    fun hasSpeechContent(samples: ShortArray): Boolean {
        if (samples.isEmpty()) return false
        val peak = peakAbs(samples)
        if (peak >= VAD_PEAK_THRESHOLD) return true

        val frameLen = SAMPLE_RATE * 20 / 1000
        if (frameLen <= 0) return peak > 0
        var speech = 0
        var total = 0
        var i = 0
        while (i + frameLen <= samples.size) {
            if (frameRms(samples, i, frameLen) >= VAD_RMS_THRESHOLD) speech++
            total++
            i += frameLen
        }
        if (total == 0) return peak > 100
        return (speech.toFloat() / total) >= VAD_SPEECH_FRAME_RATIO
    }

    fun peakAbs(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        return frameRms(samples, 0, samples.size)
    }

    /** 16-bit little-endian PCM bytes → ShortArray */
    fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices)
            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
        return shorts
    }

    /** ShortArray → 16-bit little-endian PCM bytes */
    fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun applyPreEmphasis(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val out = ShortArray(samples.size)
        out[0] = samples[0]
        for (i in 1 until samples.size) {
            val v = samples[i].toFloat() - PRE_EMPHASIS_ALPHA * samples[i - 1].toFloat()
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun normalize(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val peak = peakAbs(samples)
        if (peak == 0) return samples
        val target = (Short.MAX_VALUE * NORMALIZE_TARGET).toInt()
        if (peak >= target) return samples
        val gain = target.toFloat() / peak
        return ShortArray(samples.size) { i ->
            (samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun frameRms(samples: ShortArray, offset: Int, length: Int): Double {
        var sum = 0.0
        val end = (offset + length).coerceAtMost(samples.size)
        val n = end - offset
        if (n <= 0) return 0.0
        for (i in offset until end) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }
}
