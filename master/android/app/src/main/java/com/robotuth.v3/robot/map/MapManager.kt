package com.robotuth.v3.robot.map

import org.json.JSONObject

/**
 * Map-manager seam for the robot link, mirroring the Map manager card in the
 * operator console. Two implementations:
 *  - [OrionMapManager] — real CoreService calls (startCreatingMap, switchMap, …).
 *  - [SimMapManager]   — in-memory maps/places so the whole flow is testable on
 *                        phones/emulators without a robot.
 *
 * Replies are JSON objects already shaped for the relay (`map_operation_status`,
 * `map_list`, `place_list`, `current_map`); the link client just sends them.
 */
interface MapManager {

    enum class Lifecycle(val wireName: String) {
        IDLE("idle"),
        STARTING("starting"),
        ACTIVE("active"),
        SAVING("saving"),
        FAILED("failed")
    }

    interface Listener {
        fun onMapEvent(payload: JSONObject)
    }

    var listener: Listener?

    fun isAvailable(): Boolean

    fun startCreatingMap(name: String)
    fun stopCreatingMap(name: String)
    fun switchMap(name: String)
    fun removeMap(name: String)
    fun placeAdd(name: String, x: Double, y: Double, theta: Double)
    fun placeDelete(name: String)
    fun requestMapList()
    fun requestPlaceList()
    fun requestCurrentMap()

    /** True between a successful startCreatingMap and the matching stop. */
    fun isMapping(): Boolean

    /**
     * Last-known map name / places, used to fill the `register` frame so a
     * freshly opened console shows real data without waiting for a poll.
     * Null when nothing has been read from the robot yet.
     */
    fun advertisedState(): JSONObject? = null

    /** Last-known current map name, or null if not read yet. */
    fun currentMapName(): String? = null
}
