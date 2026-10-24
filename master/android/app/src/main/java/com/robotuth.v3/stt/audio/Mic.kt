package com.robotuth.v3.stt.audio

import android.media.AudioRecord

/**
 * Mic — bề mặt AudioRecord mà ParakeetSttService thực sự dùng (read/start/stop/
 * release + 2 trạng thái). Tách ra để debug build có thể thay bằng nguồn PCM từ
 * file ([WavFileMic]) mà không đụng một call-site nào trong recordLoop/
 * recordLoopStreaming/drainMic hay WakeMonitor.loop. Production: [AudioRecordMic] — wrapper
 * 1-1 của AudioRecord, hành vi y hệt như trước khi có interface này.
 */
interface Mic {
    /** true = thiết bị đã init thành công (AudioRecord.state == STATE_INITIALIZED). */
    val stateInitialized: Boolean

    /** true = đang ghi (AudioRecord.recordingState == RECORDSTATE_RECORDING). */
    val recording: Boolean

    fun startRecording()

    /** Đọc tối đa [len] byte vào [b] tại [off]; trả số byte đọc được, <0 = lỗi. */
    fun read(b: ByteArray, off: Int, len: Int): Int

    fun stop()

    fun release()
}

class AudioRecordMic(val record: AudioRecord) : Mic {
    override val stateInitialized: Boolean
        get() = record.state == AudioRecord.STATE_INITIALIZED
    override val recording: Boolean
        get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    override fun startRecording() = record.startRecording()
    override fun read(b: ByteArray, off: Int, len: Int): Int = record.read(b, off, len)
    override fun stop() = record.stop()
    override fun release() = record.release()
}
