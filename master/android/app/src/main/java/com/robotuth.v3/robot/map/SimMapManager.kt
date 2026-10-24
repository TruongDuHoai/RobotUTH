package com.robotuth.v3.robot.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory MapManager with the same reply shapes as [OrionMapManager], so the
 * console Map manager flow is fully exercisable on phones/emulators/CI without
 * CoreService. Maps: name → places (name → x/y/theta). Note: SimMotion keeps
 * its own navigation places; this manager only models the map/place bookkeeping
 * the console sees. (The real robot's map_switch replaces live nav targets.)
 */
class SimMapManager : MapManager {

    override var listener: MapManager.Listener? = null

    private val maps = LinkedHashMap<String, MutableMap<String, DoubleArray>>()
    private var currentMap: String? = null
    @Volatile private var mapping = false

    override fun isAvailable(): Boolean = true

    override fun isMapping(): Boolean = mapping

    override fun startCreatingMap(name: String) {
        val n = name.trim()
        when {
            mapping -> status("map_start_create", false, "already creating a map")
            n.isEmpty() -> status("map_start_create", false, "missing map name")
            maps.containsKey(n) -> status("map_start_create", false, "map '$n' exists")
            else -> {
                mapping = true
                currentMap = n
                maps[n] = mutableMapOf()
                status("map_start_create", true, "scanning as '$n' — drive the robot")
            }
        }
    }

    override fun stopCreatingMap(name: String) {
        if (!mapping) {
            status("map_stop_create", false, "not creating a map")
            return
        }
        val n = name.trim().ifEmpty { currentMap ?: "" }
        if (n.isEmpty()) {
            status("map_stop_create", false, "missing save name")
            return
        }
        val scanned = maps.remove(currentMap) ?: mutableMapOf()
        maps[n] = scanned
        currentMap = n
        mapping = false
        status("map_stop_create", true, "saved map '$n'")
    }

    override fun switchMap(name: String) {
        val m = maps[name]
        if (m == null) {
            status("map_switch", false, "no map '$name'")
            return
        }
        currentMap = name
        status("map_switch", true, "switched to '$name'")
    }

    override fun removeMap(name: String) {
        if (!maps.containsKey(name)) {
            status("map_remove", false, "no map '$name'")
            return
        }
        if (mapping && name == currentMap) {
            status("map_remove", false, "cannot remove the map being scanned")
            return
        }
        maps.remove(name)
        if (currentMap == name) currentMap = null
        status("map_remove", true, "removed '$name'")
    }

    override fun placeAdd(name: String, x: Double, y: Double, theta: Double) {
        val n = name.trim()
        if (n.isEmpty()) {
            status("place_add", false, "missing place name")
            return
        }
        val m = maps[currentMap]
        if (m == null) {
            status("place_add", false, "no current map")
            return
        }
        if (m.containsKey(n)) {
            status("place_add", false, "place '$n' exists")
            return
        }
        m[n] = doubleArrayOf(x, y, theta)
        status("place_add", true, "added '$n'")
    }

    override fun placeDelete(name: String) {
        val m = maps[currentMap]
        if (m == null || !m.containsKey(name)) {
            status("place_delete", false, "no place '$name'")
            return
        }
        m.remove(name)
        status("place_delete", true, "deleted '$name'")
    }

    override fun requestMapList() {
        listener?.onMapEvent(
            JSONObject()
                .put("type", "map_list")
                .put("maps", JSONArray(maps.keys))
                .put("current", currentMap ?: JSONObject.NULL)
                .put("mapping", mapping)
        )
    }

    override fun requestPlaceList() {
        val arr = JSONArray()
        maps[currentMap]?.toSortedMap()?.forEach { (name, p) ->
            arr.put(
                JSONObject().put("name", name)
                    .put("x", round3(p[0])).put("y", round3(p[1]))
                    .put("theta", round3(p[2]))
            )
        }
        listener?.onMapEvent(
            JSONObject().put("type", "place_list")
                .put("map", currentMap ?: JSONObject.NULL)
                .put("places", arr)
        )
    }

    override fun requestCurrentMap() {
        listener?.onMapEvent(
            JSONObject().put("type", "current_map")
                .put("map", currentMap ?: JSONObject.NULL)
                .put("mapping", mapping)
        )
    }

    private fun status(op: String, ok: Boolean, detail: String) {
        listener?.onMapEvent(
            JSONObject().put("type", "map_operation_status")
                .put("op", op)
                .put("result", if (ok) "ok" else "failed")
                .put("detail", detail)
        )
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
