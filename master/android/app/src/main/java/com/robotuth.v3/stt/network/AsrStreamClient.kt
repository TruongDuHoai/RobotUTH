package com.robotuth.v3.stt.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AsrStreamClient — WS client for the server relay `/ws/asr/{session}`
 * (streaming capture, batch decode: `server/api/asr_stream.py`).
 *
 * Chủ đích KHÔNG reconnect: mất WS giữa câu → caller rơi về POST
 * whole-utterance trên buffer PCM đã giữ trong RAM, robot vẫn trả lời.
 *
 * Hợp đồng:
 *   → binary PCM16 mono 16 kHz frame (~100 ms); text `{"type":"stop"}` = hết lượt
 *   ← `{"type":"hello"}` · `{"type":"partial","index","text",...}` ·
 *      `{"type":"final","text","transcript_raw","segments","dropped_segments",...}` ·
 *      `{"type":"error","detail","segment"?}`
 */
class AsrStreamClient {

    interface Listener {
        fun onPartial(index: Int, text: String, durationSeconds: Double, processingSeconds: Double)
        /** Final của relay — có thể rỗng ("không có tiếng"), đó không phải lỗi. */
        fun onFinal(final: JSONObject)
        /** Lỗi vận hành của stream (mất kết nối / segment bị drop). */
        fun onStreamError(detail: String)
        /**
         * Socket đóng (relay cutoff theo `max_session_s`/idle, hoặc server tắt).
         * Default no-op → caller push-to-talk không đổi hành vi; wake monitor
         * override để reconnect.
         */
        fun onClosed() {}
    }

    @Volatile private var socket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    @Volatile private var finalJson: JSONObject? = null
    @Volatile private var finalLatch = CountDownLatch(1)
    private var listener: Listener? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val isHealthy: Boolean get() = open.get() && !failed.get()

    /** Blocking handshake (mic-loop thread). true nếu relay đã nhận kết nối. */
    fun connect(url: String, cb: Listener): Boolean {
        listener = cb
        open.set(false)
        failed.set(false)
        finalJson = null
        finalLatch = CountDownLatch(1)
        val handshake = CountDownLatch(1)
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                handshake.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return // stale socket (RobotLinkClient guard)
                open.set(false)
                failed.set(true)
                listener?.onStreamError(t.message ?: "ws failure")
                handshake.countDown()
                finalLatch.countDown()
                listener?.onClosed()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                webSocket.close(1000, null)
                finalLatch.countDown()
                listener?.onClosed()
            }
        })
        val ok = handshake.await(8, TimeUnit.SECONDS) && open.get() && !failed.get()
        if (!ok) {
            socket?.cancel()
            socket = null
        }
        return ok
    }

    private fun handleText(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "hello" -> Log.i(TAG, "relay hello provider=${msg.optString("provider")} " +
                "segment_silence_ms=${msg.optDouble("segment_silence_ms")}")
            "partial" -> listener?.onPartial(
                msg.optInt("index"),
                msg.optString("text"),
                msg.optDouble("duration_s", 0.0),
                0.0
            )
            "final" -> {
                finalJson = msg
                finalLatch.countDown()
                listener?.onFinal(msg)
            }
            "error" -> listener?.onStreamError(
                msg.optString("detail", "stream error") +
                    if (msg.has("segment")) " (seg ${msg.getInt("segment")})" else ""
            )
        }
    }

    fun sendFrame(pcm: ByteArray): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(pcm.toByteString()) }.getOrDefault(false)
    }

    fun sendStop() {
        socket?.send(STOP_FRAME)
    }

    /** Chờ `final` sau khi [sendStop]; null = timeout (caller fallback batch). */
    fun awaitFinal(timeoutMs: Long): JSONObject? {
        finalLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return finalJson
    }

    fun closeNow() {
        listener = null
        val ws = socket
        socket = null
        open.set(false)
        runCatching { ws?.close(1000, null) }
    }

    companion object {
        private const val TAG = "AsrStreamClient"
        private const val STOP_FRAME = "{\"type\":\"stop\"}"
    }
}
