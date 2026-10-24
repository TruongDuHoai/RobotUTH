package com.robotuth.v3.stt.audio

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/** Ghi PCM data vào file WAV (16-bit, mono, little-endian). */
object WavWriter {

    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SAMPLE = 2

    fun write(file: File, pcm: ByteArray, dataLen: Int) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeBytes("RIFF"); out.write(intLE(dataLen + 36))
            out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.write(intLE(16))
            out.write(shortLE(1))                           // PCM
            out.write(shortLE(1))                           // mono
            out.write(intLE(SAMPLE_RATE))
            out.write(intLE(SAMPLE_RATE * BYTES_PER_SAMPLE))
            out.write(shortLE(BYTES_PER_SAMPLE))
            out.write(shortLE(16))                          // bits/sample
            out.writeBytes("data"); out.write(intLE(dataLen))
            out.write(pcm, 0, dataLen)
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
}
