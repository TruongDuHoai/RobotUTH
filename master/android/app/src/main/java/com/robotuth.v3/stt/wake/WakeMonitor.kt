package com.robotuth.v3.stt.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import androidx.core.content.ContextCompat
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.data.stt.SttEvent
import com.robotuth.v3.stt.ParakeetSttService
import com.robotuth.v3.stt.audio.Mic
import com.robotuth.v3.stt.network.AsrStreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * WakeMonitor — hands-free "hello" gate trên luồng streaming, mic + relay
 * sống **liên tục** với job riêng.
 *
 * Tách khỏi [ParakeetSttService] để push-to-talk đã kiểm chứng không bị đụng:
 * record (`Mic`) và relay client là của riêng monitor; service chỉ cho mượn
 * [openMic]/[buildStreamUrl] và nhận [SttEvent] qua [emit]. Server không chứa
 * chính sách wake: relay là transport thuần, gate chạy ở đây.
 */
class WakeMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (SttEvent) -> Unit,
    private val openMic: (bufferSize: Int) -> Mic?,
    private val buildStreamUrl: (sessionId: String, turnEndAuto: Boolean) -> String,
    private val isManualRecording: () -> Boolean,
) {
    @Volatile var isListening = false
        private set

    private var record: Mic? = null
    private var client: AsrStreamClient? = null
    private var job: Job? = null

    fun start(): Boolean {
        if (!AppConfig.STREAMING_ASR_ENABLED || !AppConfig.WAKE_WORD_ENABLED) return false
        if (isListening) return true
        if (isManualRecording()) {
            Log.w(TAG, "wake: manual recording active — không chiếm mic")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "wake: thiếu quyền RECORD_AUDIO")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf <= 0) {
            Log.w(TAG, "wake: thiết bị không hỗ trợ 16k mono (minBuf=$minBuf)")
            return false
        }
        val mic = openMic(maxOf(minBuf * 4, CHUNK_BYTES))
        if (mic == null) {
            Log.w(TAG, "wake: openMic thất bại")
            return false
        }
        try {
            mic.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "wake: startRecording: ${e.message}")
            try { mic.release() } catch (_: Exception) {}
            return false
        }
        if (!mic.recording) {
            try { mic.release() } catch (_: Exception) {}
            Log.w(TAG, "wake: mic không vào RECORDING")
            return false
        }
        record = mic
        isListening = true
        Log.i(TAG, "wake monitor started")
        job = scope.launch { loop() }
        return true
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        job?.cancel()
        job = null
        client?.closeNow()
        client = null
        releaseRecord()
    }

    private fun releaseRecord() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        // Cùng routing reset như manual — nhưng KHÔNG đụng streamClient của turn.
    }

    private suspend fun loop() {
        val readBuf = ByteArray(ParakeetSttService.READ_BYTES)
        val finals = ConcurrentLinkedQueue<String>()
        var awaitingFollowup = false
        var followupUntilMs = 0L
        val gate = WakeWordGate(
            AppConfig.WAKE_WORD_PATTERNS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        )
        try {
            while (isListening) {
                finals.clear()
                val relay = AsrStreamClient()
                val listener = object : AsrStreamClient.Listener {
                    override fun onPartial(index: Int, text: String, durationSeconds: Double, processingSeconds: Double) {
                        if (awaitingFollowup && text.any { it.isLetterOrDigit() }) {
                            emit(SttEvent.CtcPartial(text, durationSeconds, processingSeconds))
                        }
                    }

                    override fun onFinal(final: org.json.JSONObject) {
                        val t = final.optString("text").trim()
                        if (t.isNotEmpty()) finals.offer(t)
                    }

                    override fun onStreamError(detail: String) {
                        Log.w(TAG, "wake relay: $detail")
                    }

                    override fun onClosed() {
                        // mic loop thấy !isHealthy → reconnect
                    }
                }
                val url = buildStreamUrl(newSessionId(), true)
                if (!relay.connect(url, listener)) {
                    relay.closeNow()
                    if (!isListening) break
                    delay(WAKE_RECONNECT_DELAY_MS)
                    continue
                }
                client = relay
                var readFails = 0
                var micDead = false
                while (isListening && relay.isHealthy) {
                    val n = try {
                        record?.read(readBuf, 0, readBuf.size) ?: -1
                    } catch (e: Exception) {
                        -1
                    }
                    if (n > 0) {
                        readFails = 0
                        if (!relay.sendFrame(readBuf.copyOfRange(0, n))) break
                        if (awaitingFollowup && System.currentTimeMillis() > followupUntilMs) {
                            awaitingFollowup = false
                            emit(SttEvent.StatusUpdate("Đang chờ “${AppConfig.WAKE_WORD_LABEL}”..."))
                        }
                        while (true) {
                            val text = finals.poll() ?: break
                            if (awaitingFollowup) {
                                awaitingFollowup = false
                                emit(SttEvent.WakeDetected(text))
                            } else {
                                val match = gate.evaluate(text) ?: continue
                                if (match.question.isBlank()) {
                                    emit(SttEvent.WakeDetected(""))
                                    emit(SttEvent.StatusUpdate("Robot nghe đây — bạn nói đi ạ"))
                                    awaitingFollowup = true
                                    followupUntilMs =
                                        System.currentTimeMillis() + AppConfig.WAKE_FOLLOWUP_WINDOW_MS
                                } else {
                                    emit(SttEvent.WakeDetected(match.question))
                                }
                            }
                        }
                    } else if (++readFails >= 16) {
                        micDead = true
                        break
                    }
                }
                relay.closeNow()
                client = null
                if (micDead) {
                    Log.e(TAG, "wake: mic read lỗi liên tiếp — dừng monitor")
                    emit(SttEvent.Error("Micro lỗi — wake off", isFatal = false))
                    break
                }
                if (isListening) delay(300) // reconnect sau relay cutoff (max_session_s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "wakeLoop error", e)
        } finally {
            isListening = false
            client?.closeNow()
            client = null
            releaseRecord()
        }
    }

    private fun newSessionId() = "ses_${System.currentTimeMillis()}"

    companion object {
        private const val TAG = "WakeMonitor"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_MS = 2_500L
        private val CHUNK_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MS / 1000).toInt()

        /** Delay giữa các lần thử reconnect (relay cutoff/mất mạng). */
        private const val WAKE_RECONNECT_DELAY_MS = 2_000L
    }
}
