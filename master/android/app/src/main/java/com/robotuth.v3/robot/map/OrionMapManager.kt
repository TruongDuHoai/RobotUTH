package com.robotuth.v3.robot.map

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.actionbean.PlaceBean
import com.ainirobot.coreservice.client.actionbean.StartCreateMapBean
import com.ainirobot.coreservice.client.actionbean.StopCreateMapBean
import com.ainirobot.coreservice.client.listener.CommandListener
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.stt.audio.OrionHomeCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

@Suppress("DEPRECATION")
class OrionMapManager(private val context: Context) : MapManager {

    override var listener: MapManager.Listener? = null

    private val reqSeq = AtomicLong(30_000)
    @Volatile private var lifecycle = MapManager.Lifecycle.IDLE
    @Volatile private var currentMapName: String? = null
    @Volatile private var pendingMapName: String? = null
    @Volatile private var lastPlaces: JSONArray? = null

    override fun currentMapName(): String? = currentMapName

    /** Cached map/places for the `register` frame — see [MapManager.advertisedState]. */
    override fun advertisedState(): JSONObject? {
        val map = currentMapName
        val places = lastPlaces
        if (map == null && places == null) return null
        return JSONObject().apply {
            if (map != null) put("map", map) else put("map", JSONObject.NULL)
            if (places != null) put("places", places)
        }
    }

    override fun isAvailable(): Boolean = OrionHomeCompat.hasCoreService(context)
    override fun isMapping(): Boolean = lifecycle == MapManager.Lifecycle.STARTING ||
        lifecycle == MapManager.Lifecycle.ACTIVE || lifecycle == MapManager.Lifecycle.SAVING

    override fun startCreatingMap(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return status("map_start_create", false, "missing map name", phase = "preflight")
        if (isMapping()) return status("map_start_create", false, "map lifecycle is busy", phase = "preflight")

        val previous = lifecycle
        lifecycle = MapManager.Lifecycle.STARTING
        pendingMapName = n
        val rc = call("map_start_create") { reqId ->
            RobotApi.getInstance().startCreatingMap(
                reqId,
                StartCreateMapBean().setMapName(n).setMapLanguage(AppConfig.MAP_LANGUAGE),
                object : CommandListener() {
                    override fun onResult(result: Int, data: String?) {
                        if (result == 0) {
                            lifecycle = MapManager.Lifecycle.ACTIVE
                            currentMapName = n
                            pendingMapName = null
                            status("map_start_create", true, "scanning as '$n' — drive the robot", rc = 0, phase = "callback")
                        } else {
                            lifecycle = MapManager.Lifecycle.FAILED
                            pendingMapName = null
                            status("map_start_create", false, data ?: "start failed", result, "callback")
                        }
                    }
                    override fun onError(errorCode: Int, errorString: String?) {
                        lifecycle = previous
                        pendingMapName = null
                        status("map_start_create", false, errorString ?: "callback error", errorCode, "callback")
                    }
                }
            )
        }
        if (rc == 0) status("map_start_create", true, "request accepted", rc = 0, phase = "submit", result = "accepted")
        else {
            lifecycle = previous
            pendingMapName = null
            if (rc != null) status("map_start_create", false, "request rejected", rc, "submit")
        }
    }

    override fun stopCreatingMap(name: String) {
        if (lifecycle != MapManager.Lifecycle.ACTIVE) return status("map_stop_create", false, "map is not active", phase = "preflight")
        val n = name.trim().ifEmpty { currentMapName ?: "" }
        if (n.isEmpty()) return status("map_stop_create", false, "missing save name", phase = "preflight")

        lifecycle = MapManager.Lifecycle.SAVING
        val rc = call("map_stop_create") { reqId ->
            RobotApi.getInstance().stopCreatingMap(
                reqId, StopCreateMapBean().setMapName(n), object : CommandListener() {
                    override fun onResult(result: Int, data: String?) {
                        if (result == 0) {
                            lifecycle = MapManager.Lifecycle.IDLE
                            currentMapName = n
                            status("map_stop_create", true, "saved map '$n'", rc = 0, phase = "callback")
                        } else {
                            lifecycle = MapManager.Lifecycle.FAILED
                            status("map_stop_create", false, data ?: "save failed", result, "callback")
                        }
                    }
                    override fun onError(errorCode: Int, errorString: String?) {
                        lifecycle = MapManager.Lifecycle.ACTIVE
                        status("map_stop_create", false, errorString ?: "callback error", errorCode, "callback")
                    }
                }
            )
        }
        if (rc == 0) status("map_stop_create", true, "request accepted", rc = 0, phase = "submit", result = "accepted")
        else {
            lifecycle = MapManager.Lifecycle.ACTIVE
            if (rc != null) status("map_stop_create", false, "request rejected", rc, "submit")
        }
    }

    override fun switchMap(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return status("map_switch", false, "missing map name", phase = "preflight")
        if (isMapping()) return status("map_switch", false, "cannot switch maps while creating a map", phase = "preflight")
        submitSimple(
            "map_switch",
            { reqId, callback -> RobotApi.getInstance().switchMap(reqId, n, callback) },
            { currentMapName = n }
        )
    }

    override fun removeMap(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return status("map_remove", false, "missing map name", phase = "preflight")
        if (isMapping()) {
            status("map_remove", false, "cannot remove maps while creating a map", phase = "preflight")
            return
        }
        submitSimple("map_remove", { reqId, callback ->
            RobotApi.getInstance().removeMap(reqId, n, callback)
        })
    }

    override fun placeAdd(name: String, x: Double, y: Double, theta: Double) {
        val n = name.trim()
        if (n.isEmpty() || !x.isFinite() || !y.isFinite() || !theta.isFinite()) {
            return status("place_add", false, "invalid place name or coordinates", phase = "preflight")
        }
        placeMutate("place_add") { places ->
            places.removeAll { it.getPlaceNameSafe() == n }
            places.add(PlaceBean().apply {
                setPlaceId(n); setPointX(x.toFloat()); setPointY(y.toFloat()); setPointTheta(theta.toFloat())
            })
            true
        }
    }

    override fun placeDelete(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return status("place_delete", false, "missing place name", phase = "preflight")
        placeMutate("place_delete") { places ->
            if (!places.removeAll { it.getPlaceNameSafe() == n }) {
                status("place_delete", false, "no place '$n'", phase = "validate")
                false
            } else true
        }
    }

    override fun requestMapList() {
        val root = java.io.File(android.os.Environment.getExternalStorageDirectory(), "robot/map")
        val listed = try { root.listFiles() } catch (e: Throwable) {
            Log.w(TAG, "map dir list failed", e); null
        }
        if (listed == null) {
            status("map_list", false, "local map directory is unreadable; no fallback promoted", phase = "read")
            return
        }
        val maps = JSONArray()
        listed.filter { it.isDirectory }.map { it.name }.sorted().forEach { maps.put(it) }
        listener?.onMapEvent(JSONObject().put("type", "map_list").put("maps", maps)
            .put("current", currentMapName ?: JSONObject.NULL).put("mapping", isMapping())
            .put("state", lifecycle.wireName).put("source", "local_storage"))
    }

    override fun requestPlaceList() {
        val map = currentMapName
        val rc = call("place_list") { reqId ->
            RobotApi.getInstance().getPlaceListByMapName(reqId, map ?: "", object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    // Vendor quirk: read APIs answer rc=1 yet still deliver a valid
                    // payload. Trust the payload, not the code — only report failure
                    // when there is nothing parseable to hand to the console.
                    val parsed = data?.let { tryParsePlaceJson(it) }
                    if (parsed == null) {
                        status("place_list", false, data ?: "read failed", result, "callback")
                        return
                    }
                    listener?.onMapEvent(JSONObject().put("type", "place_list")
                        .put("map", map ?: JSONObject.NULL).put("places", parsed))
                    lastPlaces = parsed
                    if (result != 0) status("place_list", true, "read returned rc=$result with ${parsed.length()} places",
                        result, "callback", result = "ok")
                }
                override fun onError(errorCode: Int, errorString: String?) =
                    status("place_list", false, errorString ?: "callback error", errorCode, "callback")
            })
        }
        if (rc != null && rc != 0) status("place_list", false, "request rejected", rc, "submit")
    }

    override fun requestCurrentMap() {
        val rc = call("current_map") { reqId ->
            RobotApi.getInstance().getMapName(reqId, object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    // Same vendor quirk as place_list: rc=1 still carries the map name.
                    if (data.isNullOrBlank()) {
                        status("current_map", false, "empty map name (rc=$result)", result, "parse")
                        return
                    }
                    currentMapName = data
                    listener?.onMapEvent(JSONObject().put("type", "current_map").put("map", data)
                        .put("mapping", isMapping()).put("state", lifecycle.wireName))
                    // Places are per-map; pull them once now that we know which map is loaded
                    // so a late `register`/console open already carries real data.
                    if (lastPlaces == null) requestPlaceList()
                    if (result != 0) status("current_map", true, "map name read with rc=$result",
                        result, "callback", result = "ok")
                }
                override fun onError(errorCode: Int, errorString: String?) =
                    status("current_map", false, errorString ?: "callback error", errorCode, "callback")
            })
        }
        if (rc != null && rc != 0) status("current_map", false, "request rejected", rc, "submit")
    }

    private fun placeMutate(op: String, mutate: (MutableList<PlaceBean>) -> Boolean) {
        val map = currentMapName
        if (map.isNullOrBlank()) return status(op, false, "no current map", phase = "preflight")
        val rc = call(op) { reqId ->
            RobotApi.getInstance().getPlaceListByMapName(reqId, map, object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    if (result != 0) return status(op, false, data ?: "read failed", result, "read_callback")
                    val json = parsePlaceJson(data, op) ?: return
                    val places = parsePlaces(json, op) ?: return
                    if (mutate(places)) writePlaces(op, map, places)
                }
                override fun onError(errorCode: Int, errorString: String?) =
                    status(op, false, errorString ?: "read callback error", errorCode, "read_callback")
            })
        }
        if (rc != null && rc != 0) status(op, false, "read request rejected", rc, "read_submit")
    }

    private fun parsePlaceJson(data: String?, op: String): JSONArray? =
        tryParsePlaceJson(data).also { if (it == null) status(op, false, "invalid place data", phase = "parse") }

    /** Parse without logging a failure — lets a caller treat rc!=0-with-payload as success. */
    private fun tryParsePlaceJson(data: String?): JSONArray? = try {
        if (data == null) throw IllegalArgumentException("missing place data")
        JSONArray(data)
    } catch (e: Throwable) {
        Log.w(TAG, "place parse failed", e)
        null
    }

    private fun parsePlaces(arr: JSONArray, op: String): MutableList<PlaceBean>? = try {
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val name = when {
                o.has("name") -> o.getString("name")
                o.has("placeId") -> o.getString("placeId")
                else -> throw IllegalArgumentException("place[$i] missing name")
            }
            PlaceBean().apply {
                setPlaceId(name)
                setPointX(o.getDouble("x").toFloat())
                setPointY(o.getDouble("y").toFloat())
                setPointTheta(o.getDouble("theta").toFloat())
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "$op place decode failed", e)
        status(op, false, "invalid place entry: ${e.message}", phase = "parse")
        null
    }

    private fun writePlaces(op: String, map: String, places: List<PlaceBean>) {
        val json = JSONArray().apply { places.forEach { p -> put(JSONObject().put("name", p.getPlaceNameSafe())
            .put("x", p.pointX.toDouble()).put("y", p.pointY.toDouble()).put("theta", p.pointTheta.toDouble())) } }
        val rc = call(op) { reqId ->
            RobotApi.getInstance().savePlaceListToPlaceFile(reqId, json.toString(), object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    if (result == 0) status(op, true, "place file updated for '$map'", rc = 0, phase = "write_callback")
                    else status(op, false, data ?: "write failed", result, "write_callback")
                }
                override fun onError(errorCode: Int, errorString: String?) =
                    status(op, false, errorString ?: "write callback error", errorCode, "write_callback")
            })
        }
        if (rc == 0) status(op, true, "write request accepted", rc = 0, phase = "write_submit", result = "accepted")
        else if (rc != null) status(op, false, "write request rejected", rc, "write_submit")
    }

    private fun submitSimple(op: String, invoke: (Int, CommandListener) -> Int, onSuccess: () -> Unit = {}) {
        val callback = object : CommandListener() {
            override fun onResult(result: Int, data: String?) {
                if (result == 0) { onSuccess(); status(op, true, data ?: "completed", rc = 0, phase = "callback") }
                else status(op, false, data ?: "operation failed", result, "callback")
            }
            override fun onError(errorCode: Int, errorString: String?) =
                status(op, false, errorString ?: "callback error", errorCode, "callback")
        }
        val rc = call(op) { invoke(it, callback) }
        if (rc == 0) status(op, true, "request accepted", rc = 0, phase = "submit", result = "accepted")
        else if (rc != null) status(op, false, "request rejected", rc, "submit")
    }

    private fun PlaceBean.getPlaceNameSafe(): String = try { placeId ?: "" } catch (_: Throwable) { "" }

    /** Null means preflight/exception was already reported; zero means accepted, not completed. */
    private fun call(op: String, invoke: (Int) -> Int): Int? {
        if (!isAvailable()) { status(op, false, "CoreService unavailable", phase = "preflight"); return null }
        return try {
            val api = RobotApi.getInstance()
            if (!api.isApiConnectedService) { status(op, false, "CoreService not connected", phase = "preflight"); null }
            else invoke(reqSeq.incrementAndGet().toInt())
        } catch (e: Throwable) {
            Log.w(TAG, "$op call failed", e)
            status(op, false, e.message ?: "call failed", phase = "submit")
            null
        }
    }

    private fun status(op: String, ok: Boolean, detail: String, rc: Int? = null,
                       phase: String = "local", result: String = if (ok) "ok" else "failed") {
        listener?.onMapEvent(JSONObject().put("type", "map_operation_status").put("op", op)
            .put("result", result).put("detail", detail).put("phase", phase)
            .put("state", lifecycle.wireName).put("rc", rc ?: JSONObject.NULL)
            .put("vendor_symbol", vendorSymbol(rc) ?: JSONObject.NULL))
    }

    private fun vendorSymbol(rc: Int?): String? = when (rc) {
        0 -> "ACTION_RESPONSE_SUCCESS"
        -1 -> "ACTION_RESPONSE_NOT_SUPPORT_ACTION"
        -2 -> "ACTION_RESPONSE_ALREADY_RUN"
        -3 -> "ACTION_RESPONSE_REQUEST_TIMEOUT"
        -4 -> "ACTION_RESPONSE_REQUEST_BUSY"
        -5 -> "ACTION_RESPONSE_REQUEST_ERROR"
        -6 -> "ACTION_RESPONSE_REQUEST_RES_ERROR"
        -7 -> "ACTION_RESPONSE_SEND_STOP_CMD_ERROR"
        -8 -> "ACTION_RESPONSE_PERMISSION_DENIED"
        -9 -> "ACTION_RESPONSE_RESOURCE_UNAVAILABLE"
        else -> null
    }

    private companion object { const val TAG = "OrionMapManager" }
}
