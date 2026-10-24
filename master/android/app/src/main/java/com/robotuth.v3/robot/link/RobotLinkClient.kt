package com.robotuth.v3.robot.link

import android.os.SystemClock
import android.util.Log
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.map.MapManager
import com.robotuth.v3.robot.motion.CommandOutcome
import com.robotuth.v3.robot.motion.RobotMotion
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Phase-1 robot link per the remote-control spec: one persistent OUTBOUND WebSocket
 * to the server (`/ws/robot/{id}`), register → heartbeat → auto-reconnect. The server
 * relays operator commands; this client hands them to [RobotMotion].
 *
 * Reconnect: bounded exponential backoff (1s→30s) with jitter; reset after a stable
 * minute. On any connection loss manual motion stops locally (watchdog + explicit stop),
 * and navigation state is reported by the motion implementation — never replayed.
 */
class RobotLinkClient(
    private val robotId: String,
    private val wsBase: String,
    private val token: String,
    private val motion: RobotMotion,
    private val maps: MapManager? = null,
    /** Returns a JSONObject with the robot's health snapshot (e.g. battery) or null. */
    private val statusProvider: (() -> JSONObject?)? = null,
    private val readinessProvider: (() -> JSONObject?)? = null,
) : WebSocketListener(), RobotMotion.Listener, MapManager.Listener {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // WS-level keepalive, not the motion watchdog
        .build()
    @Volatile private var ws: WebSocket? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var heartbeatFuture: ScheduledFuture<*>? = null
    @Volatile private var reconnectFuture: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile private var attempts = 0
    @Volatile private var connectedAtMs = 0L

    fun start() {
        if (running) return
        running = true
        motion.listener = this
        maps?.listener = this
        // Position polling: OrionMotion has no push telemetry; SimMotion emits on its own
        // loop, so only poll when the motion implementation declares no self-emission.
        if (motion is com.robotuth.v3.robot.motion.OrionMotion) {
            scheduler.scheduleWithFixedDelay({
                try {
                    motion.pollPosition()
                } catch (e: Throwable) {
                    Log.d(TAG, "pollPosition: ${e.message}")
                }
            }, 1, 1, TimeUnit.SECONDS)
        }
        connect()
        Log.i(TAG, "robot link starting robotId=$robotId base=$wsBase")
    }

    fun stop() {
        if (!running) return
        running = false
        heartbeatFuture?.cancel(false)
        reconnectFuture?.cancel(false)
        scheduler.shutdownNow()
        try {
            motion.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "motion stop on link shutdown: ${e.message}")
        }
        ws?.close(1000, "client stopping")
        ws = null
        Log.i(TAG, "robot link stopped")
    }

    private fun connect() {
        if (!running) return
        val url = buildString {
            append(wsBase.trimEnd('/'))
            append("/robot/")
            append(java.net.URLEncoder.encode(robotId, "UTF-8"))
            if (token.isNotBlank()) append("?token=").append(token)
        }
        try {
            ws = client.newWebSocket(Request.Builder().url(url).build(), this)
        } catch (e: Throwable) {
            Log.w(TAG, "ws create failed: ${e.message}")
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (!running || webSocket !== ws) {
            webSocket.close(1000, "stale connection")
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        heartbeatFuture?.cancel(false)
        attempts = 0
        connectedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "robot link connected robotId=$robotId")
        val register = JSONObject().put("type", "register").put("robot_id", robotId)
        statusProvider?.invoke()?.let { register.put("battery", it) }
        readinessProvider?.invoke()?.let { register.put("readiness", it) }
        // Advertise the real speed envelope (Orion SDK NavigationAdvancedBean limits) so the
        // console sizes its slider to what this robot supports instead of a hardcoded range.
        // Real map/places if the manager already read them; otherwise the console
        // falls back to its own polling (map_list/current_map/place_list on open).
        maps?.advertisedState()?.let { state ->
            for (key in listOf("map", "places")) {
                if (state.has(key) && state.get(key) !== JSONObject.NULL) register.put(key, state.get(key))
            }
        }
        register.put(
            "speed_limits",
            JSONObject()
                .put("linear_min", AppConfig.ROBOT_LINK_MIN_LINEAR)
                .put("linear_max", AppConfig.ROBOT_LINK_MAX_LINEAR)
                .put("angular_min", AppConfig.ROBOT_LINK_MIN_ANGULAR)
                .put("angular_max", AppConfig.ROBOT_LINK_MAX_ANGULAR)
                .put("default_linear", AppConfig.ROBOT_LINK_DEFAULT_LINEAR)
        )
        send(register)
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            { sendHeartbeat(webSocket) },
            heartbeatIntervalS(), heartbeatIntervalS(), TimeUnit.SECONDS
        )
    }

    private fun heartbeatIntervalS(): Long = 10L

    fun sendReadiness(snapshot: JSONObject) {
        if (!running) return
        send(JSONObject().put("type", "status").put("readiness", snapshot))
    }

    private fun sendHeartbeat(webSocket: WebSocket) {
        if (!running || webSocket !== ws) return
        try {
            if (SystemClock.elapsedRealtime() - connectedAtMs > STABLE_RESET_MS) attempts = 0
            send(JSONObject().put("type", "heartbeat"))
            // Battery drifts slowly; piggyback a status frame on every heartbeat.
            val status = JSONObject().put("type", "status")
            statusProvider?.invoke()?.let { status.put("battery", it) }
            readinessProvider?.invoke()?.let { status.put("readiness", it) }
            if (status.length() > 1) send(status)
        } catch (e: Throwable) {
            Log.d(TAG, "heartbeat: ${e.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket !== ws) return
        val msg = try {
            JSONObject(text)
        } catch (e: Throwable) {
            sendError("malformed_json", text.take(120))
            return
        }
        when (msg.optString("type")) {
            "heartbeat", "register" -> Unit
            "stop" -> sendOutcome(motion.stop(), msg.optString("command_id", "stop"))
            "stop_navigation" ->
                sendOutcome(motion.stopNavigation(), msg.optString("command_id", "stop_navigation"))
            "move" -> sendOutcome(
                motion.move(
                    msg.optString("direction"),
                    msg.optDouble("speed", 0.2).toFloat()
                ),
                msg.optString("command_id", ""),
            )
            "navigate" -> {
                if (maps?.isMapping() == true) {
                    sendError("mapping_active", "cannot navigate while creating a map",
                        msg.optString("command_id", "navigate"), "navigate")
                    return
                }
                sendOutcome(
                    motion.navigate(msg.optString("destination")),
                    msg.optString("command_id", "navigate"),
                )
            }
            "drive_to" -> {
                if (maps?.isMapping() == true) {
                    sendError("mapping_active", "cannot navigate while creating a map",
                        msg.optString("command_id", "navigate"), "navigate")
                    return
                }
                val x = msg.optDouble("x", Double.NaN)
                val y = msg.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite() || !msg.optDouble("theta", 0.0).isFinite()) {
                    sendError("invalid_destination", "x=${msg.opt("x")} y=${msg.opt("y")}",
                        msg.optString("command_id", "drive_to"), "drive_to")
                    return
                }
                sendOutcome(
                    motion.driveTo(x, y, msg.optDouble("theta", 0.0)),
                    msg.optString("command_id", "drive_to"),
                )
            }
            "map_start_create" -> maps?.startCreatingMap(msg.optString("name"))
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "map_start_create")
            "map_stop_create" -> maps?.stopCreatingMap(msg.optString("name"))
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "map_stop_create")
            "map_switch" -> maps?.switchMap(msg.optString("name"))
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "map_switch")
            "map_remove" -> maps?.removeMap(msg.optString("name"))
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "map_remove")
            "map_list" -> maps?.requestMapList()
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "map_list")
            "place_list" -> maps?.requestPlaceList()
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "place_list")
            "current_map" -> maps?.requestCurrentMap()
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "current_map")
            "place_add" -> maps?.placeAdd(
                msg.optString("name"), msg.optDouble("x", Double.NaN),
                msg.optDouble("y", Double.NaN), msg.optDouble("theta", Double.NaN)
            ) ?: sendError("map_unavailable", "map manager is not available",
                msg.optString("command_id"), "place_add")
            "place_delete" -> maps?.placeDelete(msg.optString("name"))
                ?: sendError("map_unavailable", "map manager is not available",
                    msg.optString("command_id"), "place_delete")
            else -> sendError("unknown_command", msg.optString("type"))
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "robot link lost: ${t.message}")
        onLinkDown(webSocket)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "robot link closed code=$code reason=$reason")
        onLinkDown(webSocket)
    }

    /** Spec: on connection loss — stop all chassis activity immediately, then reconnect. */
    @Synchronized
    private fun onLinkDown(webSocket: WebSocket) {
        if (webSocket !== ws) return
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        try {
            motion.stop()
        } catch (_: Throwable) {
        }
        ws = null
        if (running) scheduleReconnect()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!running || reconnectFuture?.isDone == false) return
        attempts += 1
        val base = min(1L shl min(attempts, 6), backoffMaxS())
        val delayS = (base * 1000 * (0.8 + 0.4 * Random.nextDouble())).toLong() / 1000
        Log.i(TAG, "reconnect in ${delayS}s (attempt $attempts)")
        reconnectFuture = scheduler.schedule({
            reconnectFuture = null
            connect()
        }, delayS, TimeUnit.SECONDS)
    }

    private fun backoffMaxS(): Long = 30L

    // ── RobotMotion.Listener → server ────────────────────────────────────────

    // Neither motion implementation reports velocity natively, so derive v/w from
    // consecutive pose samples. The operator console derives its MOVING pill from
    // them; a gap > 2 s (app suspension, poll stall) resets to "not moving".
    private var lastPose: Triple<Double, Double, Double>? = null
    private var lastPoseMs = 0L

    override fun onPosition(x: Double, y: Double, theta: Double) {
        val now = SystemClock.elapsedRealtime()
        var v = 0.0
        var w = 0.0
        val prev = lastPose
        if (prev != null) {
            val dt = (now - lastPoseMs) / 1000.0
            if (dt in 0.01..2.0) {
                v = hypot(x - prev.first, y - prev.second) / dt
                w = angleDelta(theta - prev.third) / dt
            }
        }
        lastPose = Triple(x, y, theta)
        lastPoseMs = now
        send(
            JSONObject().put("type", "position")
                .put("x", x).put("y", y).put("theta", theta)
                .put("v", round3(v)).put("w", round3(w))
        )
    }

    private fun angleDelta(d: Double): Double {
        var a = d
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    override fun onMotionEvent(type: String, data: String) {
        send(JSONObject().put("type", type).put("state", data))
    }

    /** MapManager.Listener → server (payloads already relay-shaped). */
    override fun onMapEvent(payload: JSONObject) {
        send(payload)
    }

    private fun sendOutcome(outcome: CommandOutcome, commandId: String?) {
        val payload = outcome.toJson(commandId)
        readinessProvider?.invoke()?.let { payload.put("readiness", it) }
        if (outcome.accepted) {
            payload.put("type", "ack").put("of", commandId ?: outcome.operation)
        } else {
            payload.put("type", "error")
        }
        send(payload)
    }

    private fun sendError(
        code: String,
        detail: String,
        commandId: String? = null,
        operation: String? = null,
    ) {
        val payload = JSONObject().put("type", "error").put("code", code).put("detail", detail)
        if (!commandId.isNullOrBlank()) payload.put("command_id", commandId)
        if (!operation.isNullOrBlank()) payload.put("operation", operation)
        readinessProvider?.invoke()?.let { payload.put("readiness", it) }
        send(payload)
    }

    private fun send(obj: JSONObject) {
        try {
            ws?.send(obj.toString())
        } catch (e: Throwable) {
            Log.d(TAG, "send failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "RobotLink"
        const val STABLE_RESET_MS = 60_000L
    }
}
