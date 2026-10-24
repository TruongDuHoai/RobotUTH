package com.robotuth.v3.stt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Play PCM WAV via [AudioTrack] on the voice-communication path
 * (same as AiniRobot agentservice / Xiaobao LiveKit).
 *
 * MediaPlayer + USAGE_MEDIA is often silent on Orion while LiveKit holds VOICE_COMM.
 */
class WavAudioPlayer {

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopRequested: Boolean = false

    fun stop() {
        stopRequested = true
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    /**
     * Blocking play until finished or [stop]. Returns false if file invalid / play failed.
     */
    fun playBlocking(file: File, attrs: AudioAttributes = RobotAudioSession.voiceCommAttrs()): Boolean {
        stopRequested = false
        val wav = parseWav(file) ?: run {
            Log.e(TAG, "Invalid WAV: ${file.absolutePath}")
            return false
        }
        val minBuf = AudioTrack.getMinBufferSize(
            wav.sampleRate,
            if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            if (wav.bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val bufferSize = maxOf(minBuf * 2, 4096)
        val format = AudioFormat.Builder()
            .setSampleRate(wav.sampleRate)
            .setEncoding(
                if (wav.bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT
                else AudioFormat.ENCODING_PCM_8BIT
            )
            .setChannelMask(
                if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO
                else AudioFormat.CHANNEL_OUT_STEREO
            )
            .build()

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack build failed: ${e.message}")
            return false
        }

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            audioTrack.release()
            return false
        }

        track = audioTrack
        return try {
            audioTrack.play()
            var offset = 0
            val pcm = wav.pcm
            val chunk = ByteArray(min(bufferSize, 8192))
            while (offset < pcm.size && !stopRequested) {
                val n = min(chunk.size, pcm.size - offset)
                System.arraycopy(pcm, offset, chunk, 0, n)
                var written = 0
                while (written < n && !stopRequested) {
                    val w = audioTrack.write(chunk, written, n - written)
                    if (w < 0) {
                        Log.e(TAG, "AudioTrack.write error $w")
                        return false
                    }
                    written += w
                }
                offset += n
            }
            // Drain
            if (!stopRequested) {
                try {
                    // small settle so last buffer is heard
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                }
            }
            Log.i(
                TAG,
                "play done samples=${pcm.size} rate=${wav.sampleRate} ch=${wav.channels} " +
                    "stopped=$stopRequested"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "play failed: ${e.message}", e)
            false
        } finally {
            try {
                audioTrack.pause()
            } catch (_: Exception) {
            }
            try {
                audioTrack.flush()
            } catch (_: Exception) {
            }
            try {
                audioTrack.stop()
            } catch (_: Exception) {
            }
            try {
                audioTrack.release()
            } catch (_: Exception) {
            }
            if (track === audioTrack) track = null
        }
    }

    private data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray
    )

    private fun parseWav(file: File): WavData? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                return null
            }
            var sampleRate = 16000
            var channels = 1
            var bitsPerSample = 16
            var pcm: ByteArray? = null

            while (raf.filePointer < raf.length()) {
                val chunkHdr = ByteArray(8)
                if (raf.read(chunkHdr) < 8) break
                val chunkId = String(chunkHdr, 0, 4)
                val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val safeSize = chunkSize.coerceAtLeast(0)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(safeSize)
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byte rate
                        bb.short // block align
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        if (audioFormat != 1) {
                            Log.e(TAG, "Only PCM WAV supported, format=$audioFormat")
                            return null
                        }
                    }
                    "data" -> {
                        pcm = ByteArray(safeSize)
                        raf.readFully(pcm)
                    }
                    else -> {
                        // skip unknown chunk (handle pad byte)
                        val skip = safeSize + (safeSize % 2)
                        raf.seek(raf.filePointer + skip)
                    }
                }
                if (pcm != null && sampleRate > 0) break
            }
            val data = pcm ?: return null
            if (data.isEmpty()) return null
            return WavData(sampleRate, channels, bitsPerSample, data)
        }
    }

    companion object {
        private const val TAG = "WavAudioPlayer"
    }
}
