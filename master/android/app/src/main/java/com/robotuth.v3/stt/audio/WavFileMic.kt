package com.robotuth.v3.stt.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavFileMic — nguồn PCM thay mic cho **debug build** (AVD E2E).
 *
 * Phát lại một file WAV 16 kHz mono 16-bit từng frame như mic thật:
 * mỗi [read] trả đúng số byte yêu cầu, pace 100 ms/3200 B (`Thread.sleep`),
 * hết file → **im lặng vĩnh viễn** (endpointer phía relay tự đóng segment cuối,
 * phiên wake sống tới max_session như với mic thật).
 *
 * Kích hoạt qua `Settings.Global` `robotuth_fake_mic` = đường dẫn file — đổi
 * file không cần build lại. Không đường production nào chạm tới class này.
 */
class WavFileMic private constructor(
    val path: String,
    private val pcm: ByteArray,
    private val paced: Boolean
) : Mic {

    private var pos = 0
    @Volatile private var recFlag = false
    @Volatile private var released = false

    override val stateInitialized: Boolean get() = !released
    override val recording: Boolean get() = recFlag

    override fun startRecording() {
        recFlag = true
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!recFlag || released) return -1
        if (len <= 0) return 0
        val take = minOf(len, pcm.size - pos).coerceAtLeast(0)
        if (take > 0) pcm.copyInto(b, off, pos, pos + take)
        if (take < len) b.fill(0, off + take, off + len)
        pos += take
        // 3200 B = 100 ms @ 16 kHz mono 16-bit → sleep theo byte tiêu thụ.
        val consumedMs = len / 32L
        if (paced && consumedMs > 0) {
            try {
                Thread.sleep(consumedMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        return len
    }

    override fun stop() {
        recFlag = false
    }

    override fun release() {
        recFlag = false
        released = true
    }

    companion object {
        private const val TAG = "WavFileMic"
        private const val EXPECTED_RATE = 16000
        private const val EXPECTED_BITS = 16
        private const val EXPECTED_CHANNELS = 1

        /**
         * @return null nếu file không đọc được hoặc sai format — service phải
         *         fail LOÀI chứ không âm thầm rơi về mic thật khi đang test.
         */
        fun open(path: String, paced: Boolean = true): WavFileMic? {
            val file = File(path)
            if (!file.isFile || file.length() < 44) {
                Log.e(TAG, "fake mic: không có file WAV ở $path")
                return null
            }
            val pcm = parsePcm16Mono16k(file) ?: return null
            Log.i(TAG, "fake mic: ${pcm.size} B (~${pcm.size / 32} ms) từ $path paced=$paced")
            return WavFileMic(path, pcm, paced)
        }

        /** Chunk-walk theo đúng parseWav của WavAudioPlayer, kèm validate format. */
        private fun parsePcm16Mono16k(file: File): ByteArray? {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(12)
                    raf.readFully(header)
                    if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                        Log.e(TAG, "fake mic: không phải RIFF/WAVE")
                        return null
                    }
                    var sampleRate = 0
                    var channels = 0
                    var bits = 0
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
                                val fmt = ByteArray(minOf(safeSize, (raf.length() - raf.filePointer).toInt()))
                                raf.readFully(fmt)
                                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                                val audioFormat = bb.short.toInt() and 0xFFFF
                                channels = bb.short.toInt() and 0xFFFF
                                sampleRate = bb.int
                                bb.int // byte rate
                                bb.short // block align
                                bits = bb.short.toInt() and 0xFFFF
                                if (audioFormat != 1) {
                                    Log.e(TAG, "fake mic: chỉ hỗ trợ PCM, format=$audioFormat")
                                    return null
                                }
                            }
                            "data" -> {
                                pcm = ByteArray(minOf(safeSize, (raf.length() - raf.filePointer).toInt()))
                                raf.readFully(pcm)
                            }
                            else -> {
                                val skip = safeSize + (safeSize % 2)
                                raf.seek((raf.filePointer + skip).coerceAtMost(raf.length()))
                            }
                        }
                        if (pcm != null) break
                    }
                    val data = pcm
                    if (data == null || data.isEmpty()) {
                        Log.e(TAG, "fake mic: thiếu chunk data")
                        return null
                    }
                    if (sampleRate != EXPECTED_RATE || channels != EXPECTED_CHANNELS ||
                        bits != EXPECTED_BITS
                    ) {
                        Log.e(
                            TAG,
                            "fake mic: cần 16k mono 16-bit, nhận ${sampleRate}Hz/${channels}ch/${bits}bit"
                        )
                        return null
                    }
                    return data
                }
            } catch (e: Exception) {
                Log.e(TAG, "fake mic: parse lỗi: ${e.message}")
                return null
            }
        }
    }
}
