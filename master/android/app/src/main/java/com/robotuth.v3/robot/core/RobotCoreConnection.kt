package com.robotuth.v3.robot.core

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import com.robotuth.v3.robot.ChassisArbiter
import com.robotuth.v3.stt.audio.OrionHomeCompat
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class RobotCoreConnection(context: Context) {

    enum class State { UNAVAILABLE, CONNECTING, CONNECTED, DISCONNECTED, DISABLED }

    interface Listener {
        fun onCoreStateChanged(snapshot: JSONObject)
    }

    private val appContext = context.applicationContext
    private val supported = OrionHomeCompat.hasCoreService(appContext)
    private val connecting = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var state = if (supported) State.DISCONNECTED else State.UNAVAILABLE
    @Volatile private var detail: String? = null

    /** Optional live suppliers (wired by RobotLinkService) so readiness is real, not placeholder. */
    var localizationProvider: (() -> String)? = null
    var mappingProvider: (() -> String)? = null
    var mapProvider: (() -> String?)? = null

    private val apiListener = object : ApiListener {
        override fun handleApiConnected() {
            connecting.set(false)
            state = State.CONNECTED
            detail = null
            Log.i(TAG, "CoreService connected for robot link")
            notifyListeners()
        }

        override fun handleApiDisconnected() {
            connecting.set(false)
            state = State.DISCONNECTED
            detail = "binder disconnected"
            ChassisArbiter.releaseRemote()
            Log.w(TAG, "CoreService disconnected from robot link")
            notifyListeners()
        }

        override fun handleApiDisabled() {
            connecting.set(false)
            state = State.DISABLED
            detail = "API disabled by RobotOS"
            ChassisArbiter.releaseRemote()
            Log.w(TAG, "CoreService API disabled for robot link")
            notifyListeners()
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onCoreStateChanged(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (!supported) {
            notifyListeners()
            return
        }
        try {
            val api = RobotApi.getInstance()
            if (api.isApiConnectedService) {
                connecting.set(false)
                state = State.CONNECTED
                detail = null
                notifyListeners()
                return
            }
            if (!connecting.compareAndSet(false, true)) return
            state = State.CONNECTING
            detail = null
            notifyListeners()
            api.connectServer(appContext, apiListener)
            Log.i(TAG, "connecting CoreService for robot link")
        } catch (e: Throwable) {
            connecting.set(false)
            state = State.DISCONNECTED
            detail = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "CoreService connect failed: ${e.message}", e)
            notifyListeners()
        }
    }

    fun disconnect() {
        if (!supported) return
        try {
            RobotApi.getInstance().disconnectApi()
        } catch (e: Throwable) {
            Log.d(TAG, "CoreService disconnect: ${e.message}")
        }
        connecting.set(false)
        state = State.DISCONNECTED
        detail = "client stopped"
        ChassisArbiter.releaseRemote()
        notifyListeners()
    }

    fun isConnected(): Boolean =
        state == State.CONNECTED && try {
            RobotApi.getInstance().isApiConnectedService
        } catch (_: Throwable) {
            false
        }

    fun snapshot(): JSONObject {
        val owner = ChassisArbiter.currentOwner()
        return JSONObject()
            .put("ready", state == State.CONNECTED)
            .put("core_service", state.name.lowercase())
            .put("localization", localizationProvider?.invoke() ?: "unknown")
            .put("mapping_state", mappingProvider?.invoke() ?: "unknown")
            .put("current_map", mapProvider?.invoke() ?: JSONObject.NULL)
            .put("chassis_owner", owner ?: JSONObject.NULL)
            .put("chassis_mode", owner ?: "idle")
            .put("last_rc", JSONObject.NULL)
            .put("detail", detail ?: JSONObject.NULL)
    }

    private fun notifyListeners() {
        val value = snapshot()
        listeners.forEach { listener ->
            try {
                listener.onCoreStateChanged(JSONObject(value.toString()))
            } catch (e: Throwable) {
                Log.d(TAG, "listener failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "RobotCore"
    }
}
