package com.robotuth.v3.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.robotuth.v3.BuildConfig
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.data.stt.CtcSttService
import com.robotuth.v3.data.stt.SttEvent
import com.robotuth.v3.stt.audio.AudioPipeline
import com.robotuth.v3.stt.audio.AudioRecordMic
import com.robotuth.v3.stt.audio.Mic
import com.robotuth.v3.stt.audio.RobotAudioSession
import com.robotuth.v3.stt.audio.WavFileMic
import com.robotuth.v3.stt.audio.WavWriter
import com.robotuth.v3.stt.network.AsrStreamClient
import com.robotuth.v3.stt.network.CtcHttpClient
import com.robotuth.v3.stt.wake.WakeMonitor
import com.robotuth.v3.utils.UrlHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * ParakeetSttService — CTC Vietnamese ASR.
 *
 * Mặc định **whole-utterance** (push-to-talk):
 * ghi full session → khi stop gửi **một** WAV lên server.
 *
 * VAD chạy trên raw/DC (không sau peak-normalize).
 * Gửi ASR: raw (mặc định) hoặc legacy process nếu [USE_LEGACY_PROCESS].
 */
class ParakeetSttService(private val context: Context) : CtcSttService {

    /** Enables local speech endpointing for hands-free conversation. */
    @Volatile var automaticEndpointEnabled: Boolean = false

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 64)
    override val events: Flow<SttEvent> = _events.asSharedFlow()

    private val _isListening = AtomicBoolean(false)
    override val isListening: Boolean get() = _isListening.get()

    val lastStartError: String? get() = _lastStartError.get()
    private val _lastStartError = AtomicReference<String?>(null)

    private var audioRecord: Mic? = null
    private var selectedAudioSource: Int = -1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    // Lazy: do not touch AudioManager until mic is used (Orion pip on cold start)
    private val audioSession by lazy { RobotAudioSession(context) }

    private val httpClient = CtcHttpClient().also { it.serverUrl = AppConfig.DEFAULT_CTC_SERVER_URL }
    private var sessionId = newSessionId()
    private var streamClient: AsrStreamClient? = null

    // Wake monitor chạy song song độc lập với push-to-talk — record + client
    // riêng để đường manual đã kiểm chứng không bị đụng (xem WakeMonitor).
    private val wakeMonitor = WakeMonitor(
        context, scope, ::emit, ::openAudioRecord, ::streamWsUrl, _isListening::get
    )

    /** Ghép transcript trong 1 phiên ghi (chunk mode) / single result (whole mode) */
    private val sessionTranscript = StringBuilder()
    private var lastServerDetail: String? = null

    companion object {
        private const val TAG = "ParakeetSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /**
         * true = ghi cả phiên, 1 POST khi stop (chất lượng tốt hơn).
         * false = chunk cứng (A/B so sánh WER cũ).
         */
        const val USE_WHOLE_UTTERANCE = true

        /**
         * true = DC + pre-emphasis + peak normalize (pipeline cũ).
         * false = gửi waveform gần raw (chỉ DC optional) — mặc định.
         */
        const val USE_LEGACY_PROCESS = false

        /** true = chỉ DC removal trước khi gửi; false = raw tuyệt đối. */
        const val USE_DC_REMOVAL = true

        /** Chunk mode legacy — 2.5s (chỉ khi !USE_WHOLE_UTTERANCE). */
        private const val CHUNK_MS = 2_500L

        /** Đọc mic theo frame nhỏ */
        private const val READ_MS = 100L

        /** Utterance tối thiểu ~0.4s */
        private const val MIN_UTTERANCE_MS = 400L

        /** Cap whole-utterance ~20s (khớp server MAX ~30s) */
        private const val MAX_UTTERANCE_MS = 20_000L

        /** Flush im lặng: peak raw dưới ngưỡng → bỏ */
        private const val SILENCE_PEAK_THRESHOLD = 80

        /** Hands-free endpointing: speech followed by this much silence closes the turn. */
        private const val ENDPOINT_SILENCE_MS = 900L
        private const val ENDPOINT_MIN_SPEECH_MS = 500L
        private const val ENDPOINT_PRE_ROLL_MS = 500L

        /** Chờ `final` của relay sau stop; lâu hơn nữa = rơi về đường batch. */
        private const val STREAM_FINAL_WAIT_MS = 15_000L

        /** Settings.Global key — debug build only: đường dẫn WAV thay mic (E2E AVD). */
        private const val FAKE_MIC_SETTING = "robotuth_fake_mic"

        private val CHUNK_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MS / 1000).toInt()
        /** Dùng chung với WakeMonitor — cùng 1 frame 100 ms. */
        internal val READ_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * READ_MS / 1000).toInt()
        private val MIN_UTTERANCE_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_UTTERANCE_MS / 1000).toInt()
        private val MAX_UTTERANCE_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_UTTERANCE_MS / 1000).toInt()
        private val ENDPOINT_PRE_ROLL_BYTES =
            (SAMPLE_RATE * BYTES_PER_SAMPLE * ENDPOINT_PRE_ROLL_MS / 1000).toInt()

        /**
         * AiniRobot Orion: prefer plain MIC.
         * VOICE_RECOGNITION / VOICE_COMMUNICATION fight LiveKit agentservice
         * (USAGE_VOICE_COMMUNICATION) → system "pip pip" + speaker dead after exit.
         * Do NOT open VOICE_COMMUNICATION on this platform.
         */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
            // VOICE_COMMUNICATION intentionally omitted
        )

        private fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            else -> "source=$source"
        }
    }

    override fun setServerUrl(url: String) {
        httpClient.serverUrl = UrlHelper.normalizeUrl(url)
        Log.i(TAG, "Server URL → ${httpClient.serverUrl}")
        emit(SttEvent.StatusUpdate("Server: ${httpClient.serverUrl}"))
    }

    override fun startListening(): Boolean {
        _lastStartError.set(null)
        lastServerDetail = null
        sessionTranscript.clear()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return failStart("Chưa cấp quyền micro. Vào Cài đặt → Ứng dụng → RobotUTH → Quyền → Micro.")
        }

        if (_isListening.get()) {
            Log.w(TAG, "Already listening — force stop before restart")
            stopListening()
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
            }
        }
        // Manual always wins over the wake monitor (một mic — hai AudioRecord
        // cùng mở sẽ lỗi; VM arm lại monitor khi hội thoại về Idle).
        if (wakeMonitor.isListening) {
            Log.i(TAG, "startListening: nhường mic cho manual — dừng wake monitor")
            stopWakeMonitor()
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf <= 0) {
            return failStart("Thiết bị không hỗ trợ ghi âm 16kHz mono (minBuffer=$minBuf)")
        }

        val bufferSize = maxOf(minBuf * 4, CHUNK_BYTES)

        return try {
            // Orion: do NOT request audio focus for mic — steals LiveKit focus → "pip" + dead speaker
            val record = openAudioRecord(bufferSize)
                ?: return failStart("AudioRecord khởi tạo thất bại (thử lại / đóng app khác đang dùng mic)")

            audioRecord = record
            sessionId = newSessionId()
            _isListening.set(true)
            record.startRecording()
            if (!record.recording) {
                releaseAudioRecord()
                _isListening.set(false)
                return failStart("Micro không vào trạng thái RECORDING (đang bị app khác chiếm?)")
            }
            Log.i(
                TAG,
                "startListening source=${sourceName(selectedAudioSource)} " +
                    "wholeUtt=$USE_WHOLE_UTTERANCE legacyProcess=$USE_LEGACY_PROCESS " +
                    "dcRemoval=$USE_DC_REMOVAL buffer=$bufferSize"
            )
            recordJob = scope.launch { recordLoop() }
            emit(SttEvent.StatusUpdate("Đang nghe..."))
            true
        } catch (e: SecurityException) {
            releaseAudioRecord()
            _isListening.set(false)
            failStart("Cần cấp quyền microphone")
        } catch (e: Exception) {
            releaseAudioRecord()
            _isListening.set(false)
            Log.e(TAG, "startListening failed", e)
            failStart("Lỗi khởi động micro: ${e.message}")
        }
    }

    /**
     * Nguồn mic duy nhất cho cả push-to-talk lẫn wake monitor.
     *
     * Debug build + `Settings.Global robotuth_fake_mic` = đường dẫn WAV →
     * phát lại file thay mic ([WavFileMic]) — dùng cho E2E trên AVD. FAIL LOÀI
     * (null, không rơi về mic thật) khi setting bật mà file sai: test phải biết
     * nó đang nghe file chứ không phải host mic.
     */
    private fun openAudioRecord(bufferSize: Int): Mic? {
        val fakePath = if (BuildConfig.DEBUG) {
            android.provider.Settings.Global.getString(context.contentResolver, FAKE_MIC_SETTING)
        } else {
            null
        }
        if (!fakePath.isNullOrBlank()) {
            val fake = WavFileMic.open(fakePath)
            if (fake == null) {
                Log.e(TAG, "FAKE MIC bật ($FAKE_MIC_SETTING='$fakePath') nhưng không parse được — không mở mic thật")
            } else {
                selectedAudioSource = -1
                Log.i(TAG, "FAKE MIC (debug build): $fakePath")
            }
            return fake
        }
        for (source in preferredAudioSources()) {
            try {
                val record = AudioRecordMic(AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, bufferSize
                ))
                if (record.stateInitialized) {
                    selectedAudioSource = source
                    Log.i(TAG, "AudioRecord OK source=${sourceName(source)} ($source) buffer=$bufferSize")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=${sourceName(source)} failed: ${e.message}")
            }
        }
        selectedAudioSource = -1
        return null
    }

    private fun preferredAudioSources(): IntArray {
        val hasAgentOs = runCatching {
            context.packageManager.getPackageInfo("com.ainirobot.agentservice", 0)
        }.isSuccess
        return if (hasAgentOs) AUDIO_SOURCES else intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
    }

    private fun failStart(message: String): Boolean {
        _lastStartError.set(message)
        emit(SttEvent.Error(message, isFatal = true))
        Log.e(TAG, "startListening: $message")
        return false
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        // Only reset mode — do not abandon agentservice focus (we never took it for mic)
        audioSession.resetRouting()
    }

    /**
     * Dừng ghi: set flag, vòng lặp flush utterance rồi emit FinalResult.
     * Không release mic ngay (tránh mất buffer đang đọc).
     */
    override fun stopListening() {
        if (!_isListening.getAndSet(false)) {
            return
        }
        emit(SttEvent.StatusUpdate("Đang gửi..."))
    }

    /**
     * Immediate hard stop for leave-screen / app background.
     * Cancels flush+ASR and frees AudioRecord so agentservice can reclaim speaker.
     */
    fun forceStopAndReleaseMic() {
        _isListening.set(false)
        recordJob?.cancel()
        recordJob = null
        streamClient?.closeNow()
        streamClient = null
        stopWakeMonitor()
        releaseAudioRecord()
        emit(SttEvent.StatusUpdate("Sẵn sàng"))
    }

    override fun release() {
        _isListening.set(false)
        recordJob?.cancel()
        recordJob = null
        streamClient?.closeNow()
        streamClient = null
        stopWakeMonitor()
        releaseAudioRecord()
        scope.cancel()
    }

    // ── Record loop ───────────────────────────────────────────────────────────

    private suspend fun recordLoop() {
        val readBuf = ByteArray(READ_BYTES)
        val accum = ByteArrayOutputStream()
        val tempWav = File(context.cacheDir, "parakeet_$sessionId.wav")
        var fails = 0
        var sentAny = false
        var hitMaxCap = false
        var endpointDetected = false
        var speechStarted = false
        var speechMs = 0L
        var trailingSilenceMs = 0L

        try {
            // Chế độ streaming: gửi frame qua WS, server cắt câu theo im lặng và
            // decode từng đoạn bằng đúng model batch đang chạy (chất lượng giữ
            // nguyên). true = lượt đã giải quyết; false = rơi tiếp xuống đường
            // whole-utterance bên dưới với đúng buffer accum này.
            if (AppConfig.STREAMING_ASR_ENABLED && USE_WHOLE_UTTERANCE &&
                recordLoopStreaming(readBuf, accum)
            ) {
                return
            }
            while (_isListening.get()) {
                val n = try {
                    audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                } catch (e: Exception) {
                    Log.e(TAG, "read failed", e)
                    -1
                }
                when {
                    n > 0 -> {
                        fails = 0
                        if (USE_WHOLE_UTTERANCE) {
                            val room = MAX_UTTERANCE_BYTES - accum.size()
                            if (room > 0) {
                                accum.write(readBuf, 0, minOf(n, room))
                            }
                            if (accum.size() >= MAX_UTTERANCE_BYTES) {
                                hitMaxCap = true
                                Log.i(TAG, "Hit max utterance ${MAX_UTTERANCE_MS}ms — auto stop buffer")
                                break
                            }

                            if (automaticEndpointEnabled) {
                                val frame = readBuf.copyOfRange(0, n)
                                val frameHasSpeech = AudioPipeline.hasSpeechContent(
                                    AudioPipeline.bytesToShorts(frame)
                                )
                                val frameMs = n * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                                if (frameHasSpeech) {
                                    speechStarted = true
                                    speechMs += frameMs
                                    trailingSilenceMs = 0L
                                } else if (speechStarted) {
                                    trailingSilenceMs += frameMs
                                }

                                // While waiting for speech, retain only a short pre-roll instead
                                // of filling the 20-second utterance buffer with silence.
                                if (!speechStarted && accum.size() > ENDPOINT_PRE_ROLL_BYTES) {
                                    val buffered = accum.toByteArray()
                                    accum.reset()
                                    accum.write(
                                        buffered,
                                        buffered.size - ENDPOINT_PRE_ROLL_BYTES,
                                        ENDPOINT_PRE_ROLL_BYTES
                                    )
                                }

                                if (speechMs >= ENDPOINT_MIN_SPEECH_MS &&
                                    trailingSilenceMs >= ENDPOINT_SILENCE_MS
                                ) {
                                    Log.i(TAG, "Automatic endpoint after ${trailingSilenceMs}ms silence")
                                    _isListening.set(false)
                                    endpointDetected = true
                                    emit(SttEvent.EndpointDetected)
                                    break
                                }
                            }
                        } else {
                            accum.write(readBuf, 0, n)
                        }
                    }
                    n < 0 -> {
                        if (++fails >= 8) {
                            emit(SttEvent.Error("Lỗi micro liên tiếp", true))
                            break
                        }
                    }
                }

                // Legacy chunk mode: gửi từng 2.5s khi có speech
                if (!USE_WHOLE_UTTERANCE && accum.size() >= CHUNK_BYTES) {
                    val chunk = takeBytes(accum, CHUNK_BYTES)
                    val ok = sendPcmChunk(chunk, tempWav, requireSpeech = true)
                    if (ok) sentAny = true
                }
            }

            // Stopped (or max cap): drain remaining samples
            if (!hitMaxCap && !endpointDetected) {
                drainMic(accum, readBuf, maxExtra = if (USE_WHOLE_UTTERANCE) MAX_UTTERANCE_BYTES else Int.MAX_VALUE)
            }

            if (USE_WHOLE_UTTERANCE) {
                val all = accum.toByteArray()
                accum.reset()
                if (all.size >= MIN_UTTERANCE_BYTES) {
                    val ok = sendPcmChunk(all, tempWav, requireSpeech = true)
                    if (ok) sentAny = true
                } else if (all.isNotEmpty()) {
                    Log.i(TAG, "Discard tiny utterance ${all.size} bytes (< ${MIN_UTTERANCE_MS}ms)")
                }
            } else {
                // Flush remainder chunk mode
                if (accum.size() >= MIN_UTTERANCE_BYTES) {
                    val rest = accum.toByteArray()
                    accum.reset()
                    val ok = sendPcmChunk(rest, tempWav, requireSpeech = false)
                    if (ok) sentAny = true
                } else if (accum.size() > 0) {
                    Log.i(TAG, "Discard tiny tail ${accum.size()} bytes")
                }
            }

            val finalText = sessionTranscript.toString().trim()
            if (finalText.isNotBlank()) {
                emit(SttEvent.FinalResult(finalText))
            } else if (!sentAny) {
                val detail = lastServerDetail
                val msg = when {
                    detail != null && detail.contains("kết nối", ignoreCase = true) ->
                        "Không kết nối ASR: $detail. Kiểm tra server."
                    detail != null && detail.contains("timeout", ignoreCase = true) ->
                        "ASR timeout. Server bận hoặc ASR GPU chưa chạy."
                    detail != null && detail.contains("503", ignoreCase = true) ->
                        "ASR backend không sẵn sàng (503). Bật robotuth-asr trên server."
                    detail != null ->
                        "ASR: $detail"
                    else ->
                        "Không nhận diện được giọng nói. Nói rõ hơn, giữ mic 2–3 giây rồi thả."
                }
                emit(SttEvent.Error(msg, isFatal = true))
            } else {
                emit(SttEvent.Error(
                    "Server ASR trả về rỗng. Kiểm tra ASR GPU / nói rõ hơn.",
                    isFatal = true
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "recordLoop error", e)
            emit(SttEvent.Error("Lỗi ghi âm: ${e.message}", isFatal = true))
        } finally {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Saved ASR debug WAV: ${tempWav.absolutePath}")
            } else {
                try {
                    tempWav.delete()
                } catch (_: Exception) {
                }
            }
            releaseAudioRecord()
            emit(SttEvent.StatusUpdate("Sẵn sàng"))
        }
    }

    /**
     * Vòng ghi ở chế độ streaming (`AppConfig.STREAMING_ASR_ENABLED`).
     *
     * Stream từng frame 100 ms lên relay `/ws/asr/{session}`; server endpoint theo
     * im lặng và decode mỗi đoạn bằng **chính model batch đang chạy** → partial
     * về theo vế câu, final khi stop. `accum` vẫn được ghi đầy đủ để fallback.
     *
     * @return true = lượt đã giải quyết (final emit / error có chủ đích);
     *         false = không stream được (relay chưa có, WS chết giữa câu, hoặc
     *         final quá hạn) → caller chạy tiếp đường POST whole-utterance cũ.
     */
    private fun recordLoopStreaming(readBuf: ByteArray, accum: ByteArrayOutputStream): Boolean {
        val client = AsrStreamClient()
        val url = streamWsUrl(sessionId)
        val listener = object : AsrStreamClient.Listener {
            override fun onPartial(index: Int, text: String, durationSeconds: Double, processingSeconds: Double) {
                if (text.any { it.isLetterOrDigit() }) {
                    emit(SttEvent.CtcPartial(text, durationSeconds, processingSeconds))
                }
            }

            override fun onFinal(final: org.json.JSONObject) {
                // awaitFinal() tiêu thụ; không làm gì thêm ở đây (turn manual).
            }

            override fun onStreamError(detail: String) {
                Log.w(TAG, "stream: relay: $detail")
            }
        }
        if (!client.connect(url, listener)) {
            Log.w(TAG, "stream: connect failed — whole turn goes batch")
            return false
        }
        streamClient = client
        try {
            var readFails = 0
            var broken = false
            while (_isListening.get()) {
                val n = try {
                    audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                } catch (e: Exception) {
                    Log.e(TAG, "read failed", e)
                    -1
                }
                when {
                    n > 0 -> {
                        readFails = 0
                        val room = MAX_UTTERANCE_BYTES - accum.size()
                        if (room > 0) accum.write(readBuf, 0, minOf(n, room))
                        if (accum.size() >= MAX_UTTERANCE_BYTES) {
                            Log.i(TAG, "stream: max utterance ${MAX_UTTERANCE_MS}ms — closing turn")
                            break
                        }
                        if (!client.isHealthy || !client.sendFrame(readBuf.copyOfRange(0, n))) {
                            broken = true
                            break
                        }
                    }
                    n < 0 -> if (++readFails >= 8) {
                        emit(SttEvent.Error("Lỗi micro liên tiếp", isFatal = true))
                        return true
                    }
                }
            }
            if (broken) {
                Log.w(TAG, "stream: WS lost mid-utterance — batch fallback on buffered audio")
                return false
            }
            // Đuôi mic còn đang read dở — gửi nốt để không mất âm tiết cuối.
            repeat(5) {
                if (!client.isHealthy) {
                    broken = true
                    return@repeat
                }
                val n = try {
                    audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                } catch (_: Exception) {
                    -1
                }
                if (n > 0) {
                    val room = MAX_UTTERANCE_BYTES - accum.size()
                    if (room > 0) accum.write(readBuf, 0, minOf(n, room))
                    client.sendFrame(readBuf.copyOfRange(0, n))
                }
            }
            if (broken) return false
            client.sendStop()
            emit(SttEvent.StatusUpdate("Đang gửi..."))
            val final = client.awaitFinal(STREAM_FINAL_WAIT_MS)
            if (final == null) {
                Log.w(TAG, "stream: no final within ${STREAM_FINAL_WAIT_MS}ms — batch fallback")
                return false
            }
            val text = final.optString("text").trim()
            val dropped = final.optJSONArray("dropped_segments")?.length() ?: 0
            if (text.any { it.isLetterOrDigit() }) {
                Log.i(TAG, "stream final segs=${final.optInt("segments")} dropped=$dropped text='$text'")
                // Manual mode: VM đã ở ProcessingStt → handler ignores; giữ đúng
                // sequence để bật auto mode sau này không đổi code.
                emit(SttEvent.EndpointDetected)
                emit(SttEvent.FinalResult(text))
            } else {
                Log.i(TAG, "stream final empty (dropped=$dropped)")
                emit(SttEvent.Error(
                    "Không nhận diện được giọng nói. Nói rõ hơn, giữ mic 2–3 giây rồi thả.",
                    isFatal = true
                ))
            }
            return true
        } finally {
            streamClient = null
            client.closeNow()
        }
    }

    /** serverUrl (http/https) → `ws(s)://{base}/ws/asr/{sid}?token=…[&turn_end=auto]` */
    private fun streamWsUrl(sid: String, turnEndAuto: Boolean = false): String {
        val http = httpClient.serverUrl.trimEnd('/')
        val ws = when {
            http.startsWith("https://") -> "wss://" + http.substring("https://".length)
            http.startsWith("http://") -> "ws://" + http.substring("http://".length)
            else -> http
        }
        return buildString {
            append(ws).append("/ws/asr/").append(sid).append('?')
            var first = true
            if (AppConfig.ROBOT_API_TOKEN.isNotBlank()) {
                append("token=").append(AppConfig.ROBOT_API_TOKEN)
                first = false
            }
            if (turnEndAuto) {
                if (!first) append('&')
                append("turn_end=auto")
            }
        }
    }

    // ── Wake monitor (hands-free, app-side gate trên luồng streaming) ─────────
    // Logic sống trong WakeMonitor (stt/wake) — ở đây chỉ giữ API mà
    // RobotFaceViewModel đang gọi, để push-to-talk không bị đụng.
    fun isWakeListening(): Boolean = wakeMonitor.isListening

    fun startWakeMonitor(): Boolean = wakeMonitor.start()

    fun stopWakeMonitor() = wakeMonitor.stop()

    private fun drainMic(accum: ByteArrayOutputStream, readBuf: ByteArray, maxExtra: Int) {
        val rec = audioRecord ?: return
        repeat(15) {
            if (accum.size() >= maxExtra) return
            val n = try {
                rec.read(readBuf, 0, readBuf.size)
            } catch (_: Exception) {
                -1
            }
            if (n > 0) {
                val room = maxExtra - accum.size()
                if (room > 0) accum.write(readBuf, 0, minOf(n, room))
            } else {
                return
            }
        }
    }

    private fun takeBytes(accum: ByteArrayOutputStream, count: Int): ByteArray {
        val all = accum.toByteArray()
        val take = minOf(count, all.size)
        val chunk = all.copyOfRange(0, take)
        accum.reset()
        if (take < all.size) {
            accum.write(all, take, all.size - take)
        }
        return chunk
    }

    /**
     * @param requireSpeech true = bỏ nếu VAD trên raw không thấy speech
     * @return true nếu đã POST lên server (kể cả transcript rỗng)
     */
    private suspend fun sendPcmChunk(
        pcmRaw: ByteArray,
        tempWav: File,
        requireSpeech: Boolean
    ): Boolean {
        if (pcmRaw.size < MIN_UTTERANCE_BYTES) return false

        val samples = AudioPipeline.bytesToShorts(pcmRaw)
        val peakRaw = AudioPipeline.peakAbs(samples)
        val rmsRaw = AudioPipeline.rms(samples)
        val durationMs = (samples.size * 1000.0 / SAMPLE_RATE).roundToInt()

        // VAD trên raw (trước mọi amplify)
        if (requireSpeech && !AudioPipeline.hasSpeechContent(samples)) {
            Log.i(
                TAG,
                "VAD skip source=${sourceName(selectedAudioSource)} " +
                    "dur=${durationMs}ms peakRaw=$peakRaw rmsRaw=${"%.1f".format(rmsRaw)}"
            )
            emit(SttEvent.StatusUpdate("Im lặng..."))
            return false
        }

        if (!requireSpeech && peakRaw < SILENCE_PEAK_THRESHOLD) {
            Log.i(TAG, "Flush skipped — silence peakRaw=$peakRaw")
            return false
        }

        // Chuẩn bị audio gửi ASR
        val toSend: ShortArray = when {
            USE_LEGACY_PROCESS -> AudioPipeline.process(samples)
            USE_DC_REMOVAL -> AudioPipeline.removeDcOffset(samples)
            else -> samples
        }

        val peakSend = AudioPipeline.peakAbs(toSend)
        val rmsSend = AudioPipeline.rms(toSend)
        Log.i(
            TAG,
            "send ASR source=${sourceName(selectedAudioSource)} " +
                "dur=${durationMs}ms samples=${samples.size} " +
                "peakRaw=$peakRaw rmsRaw=${"%.1f".format(rmsRaw)} " +
                "peakSend=$peakSend rmsSend=${"%.1f".format(rmsSend)} " +
                "legacy=$USE_LEGACY_PROCESS dc=$USE_DC_REMOVAL whole=$USE_WHOLE_UTTERANCE"
        )

        val pcm = AudioPipeline.shortsToBytes(toSend)
        WavWriter.write(tempWav, pcm, pcm.size)
        emit(SttEvent.StatusUpdate("Đang gửi ASR..."))

        val result = withContext(Dispatchers.IO) { httpClient.sendChunk(tempWav, sessionId) }
        return handleResult(result)
    }

    /** @return true if request reached server successfully */
    private fun handleResult(r: org.json.JSONObject?): Boolean {
        if (r == null) {
            lastServerDetail = "null response"
            return false
        }
        if (r.optBoolean("success")) {
            val text = r.optString("transcript", "").trim()
            val hasSpeechText = text.any { it.isLetterOrDigit() }
            if (hasSpeechText) {
                if (sessionTranscript.isNotEmpty()) sessionTranscript.append(' ')
                sessionTranscript.append(text)
                emit(
                    SttEvent.CtcPartial(
                        text,
                        r.optDouble("duration_seconds"),
                        r.optDouble("processing_time_seconds")
                    )
                )
            } else {
                // Parakeet may return ⁇ or punctuation only for unintelligible audio.
                // Treat it as an empty result so the conversation asks the user to repeat.
                Log.i(TAG, "ASR success but no intelligible transcript: '$text'")
            }
            return hasSpeechText
        } else {
            val detail = r.optString("detail", "error")
            lastServerDetail = detail
            emit(SttEvent.StatusUpdate("Server: $detail"))
            emit(SttEvent.Error("ASR: $detail", isFatal = false))
            return false
        }
    }

    private fun emit(event: SttEvent) {
        _events.tryEmit(event)
    }

    private fun newSessionId() = "ses_${System.currentTimeMillis()}"
}
