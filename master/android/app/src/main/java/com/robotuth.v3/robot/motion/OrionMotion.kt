package com.robotuth.v3.robot.motion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.actionbean.Pose
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.ChassisArbiter
import com.robotuth.v3.stt.audio.OrionHomeCompat
import java.util.concurrent.atomic.AtomicLong

@Suppress("DEPRECATION")
class OrionMotion(private val context: Context) : RobotMotion {

    override var listener: RobotMotion.Listener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reqSeq = AtomicLong(20_000)
    @Volatile private var moving = false
    @Volatile private var navigating = false
    @Volatile private var localization = Localization.UNKNOWN
    @Volatile private var lastPoseUnavailableMs = 0L
    @Volatile private var manualGeneration = 0L
    @Volatile private var navigationGeneration = 0L

    enum class Localization { UNKNOWN, LOCALIZED, UNLOCALIZED, ERROR }

    override fun localizationState(): String = localization.name.lowercase()

    private val watchdogRunnable = Runnable {
        if (moving) {
            Log.w(TAG, "dead-man watchdog fired — stopping manual motion")
            stopManual()
            listener?.onMotionEvent("error", "watchdog_stop")
        }
    }

    override fun isAvailable(): Boolean = OrionHomeCompat.hasCoreService(context)

    override fun move(direction: String, speed: Float): CommandOutcome {
        if (direction !in DIRECTIONS) {
            return CommandOutcome.refused("move", "invalid_direction", detail = direction)
        }
        if (!speed.isFinite()) return CommandOutcome.refused("move", "invalid_speed")
        if (!isAvailable()) return unavailable("move")
        val api = RobotApi.getInstance()
        if (!api.isApiConnectedService) return disconnected("move")
        if (!ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_MANUAL)) return busy("move")

        val linear = speed.coerceIn(
            AppConfig.ROBOT_LINK_MIN_LINEAR,
            AppConfig.ROBOT_LINK_MAX_LINEAR,
        )
        val angular = (linear / AppConfig.ROBOT_LINK_MAX_LINEAR *
            AppConfig.ROBOT_LINK_MAX_ANGULAR).coerceIn(
            AppConfig.ROBOT_LINK_MIN_ANGULAR,
            AppConfig.ROBOT_LINK_MAX_ANGULAR,
        )
        val generation = ++manualGeneration
        val reqId = reqSeq.incrementAndGet().toInt()
        val commandListener = object : CommandListener() {
            override fun onResult(result: Int, data: String?) {
                Log.d(TAG, "motion result=$result req=$reqId data=${data?.take(80)}")
            }

            override fun onError(errorCode: Int, errorString: String?) {
                if (generation != manualGeneration) return
                Log.w(TAG, "motion error=$errorCode req=$reqId msg=$errorString")
                moving = false
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                listener?.onMotionEvent("error", "motion_failed:$errorCode:${errorString.orEmpty()}")
            }
        }
        val rc = try {
            when (direction) {
                "forward" -> api.goForward(reqId, linear, commandListener)
                "backward" -> api.goBackward(reqId, linear, commandListener)
                "left" -> api.turnLeft(reqId, angular, commandListener)
                else -> api.turnRight(reqId, angular, commandListener)
            }
        } catch (e: Throwable) {
            if (generation == manualGeneration) ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
            return exception("move", e)
        }
        Log.i(TAG, "move $direction linear=$linear angular=$angular req=$reqId rc=$rc")
        if (rc != 0) {
            if (generation == manualGeneration) ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
            return vendorRefusal("move", rc)
        }
        moving = true
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, AppConfig.ROBOT_LINK_WATCHDOG_MS)
        return CommandOutcome.ok("move")
    }

    override fun stop(): CommandOutcome {
        val manualNeeded = moving || ChassisArbiter.currentOwner() == ChassisArbiter.OWNER_REMOTE_MANUAL
        val navigationNeeded = navigating || ChassisArbiter.currentOwner() == ChassisArbiter.OWNER_REMOTE_NAVIGATION
        val manual = if (manualNeeded) stopManual() else CommandOutcome.ok("stop_move", "not active")
        val nav = if (navigationNeeded) stopNavigation() else CommandOutcome.ok("stop_navigation", "not active")
        return when {
            manual.accepted && nav.accepted -> CommandOutcome.ok("emergency_stop")
            manual.accepted || nav.accepted -> CommandOutcome.refused(
                "emergency_stop", "partial_stop", detail =
                    "manual=${manual.code}/${manual.rc} navigation=${nav.code}/${nav.rc}", phase = "submit")
            else -> CommandOutcome.refused(
                "emergency_stop", "stop_failed", detail =
                    "manual=${manual.code}/${manual.rc} navigation=${nav.code}/${nav.rc}", phase = "submit")
        }
    }

    private fun stopManual(): CommandOutcome {
        handler.removeCallbacks(watchdogRunnable)
        val generation = ++manualGeneration
        moving = false
        if (!isAvailable()) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
            return unavailable("stop_move")
        }
        val api = RobotApi.getInstance()
        if (!api.isApiConnectedService) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
            return disconnected("stop_move")
        }
        return try {
            val rc = api.stopMove(reqSeq.incrementAndGet().toInt(), object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    if (generation == manualGeneration) {
                        ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                    }
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    if (generation == manualGeneration) {
                        ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                        listener?.onMotionEvent("error", "stop_move_failed:$errorCode:${errorString.orEmpty()}")
                    }
                }
            })
            if (rc == 0) {
                // The vendor API accepts stop synchronously; retain the owner until its callback
                // so a new command cannot race the outstanding stop request. Some RobotOS builds
                // omit that callback, so the bounded fallback prevents an indefinite local lock.
                handler.postDelayed({
                    if (generation == manualGeneration) {
                        ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                    }
                }, STOP_CONFIRM_TIMEOUT_MS)
                CommandOutcome.ok("stop_move")
            } else {
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                vendorRefusal("stop_move", rc)
            }
        } catch (e: Throwable) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
            exception("stop_move", e)
        }
    }

    override fun navigate(destination: String): CommandOutcome {
        if (destination.isBlank()) return CommandOutcome.refused("navigate", "invalid_destination")
        if (localization == Localization.UNLOCALIZED) {
            return CommandOutcome.refused("navigate", "not_localized", detail = "currentPose is unavailable")
        }
        if (localization == Localization.ERROR) {
            return CommandOutcome.refused("navigate", "localization_error")
        }
        return startNavigation("navigate") { api, reqId, actionListener ->
            api.startNavigation(reqId, destination, 0.25, 120_000L, actionListener)
        }
    }

    override fun driveTo(x: Double, y: Double, theta: Double): CommandOutcome {
        if (!x.isFinite() || !y.isFinite() || !theta.isFinite()) {
            return CommandOutcome.refused("drive_to", "invalid_destination")
        }
        if (localization != Localization.LOCALIZED) {
            return CommandOutcome.refused("drive_to", "not_localized", detail = "pose=${localization.name.lowercase()}")
        }
        return startNavigation("drive_to") { api, reqId, actionListener ->
            api.startNavigation(
                reqId, Pose(x.toFloat(), y.toFloat(), theta.toFloat()),
                0.25, 120_000L, actionListener,
            )
        }
    }

    private fun startNavigation(
        operation: String,
        invoke: (RobotApi, Int, ActionListener) -> Int,
    ): CommandOutcome {
        if (!isAvailable()) return unavailable(operation)
        val api = RobotApi.getInstance()
        if (!api.isApiConnectedService) return disconnected(operation)
        if (!ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_NAVIGATION)) return busy(operation)
        val generation = ++navigationGeneration
        val reqId = reqSeq.incrementAndGet().toInt()
        val actionListener = object : ActionListener() {
            override fun onResult(result: Int, data: String?) {
                if (generation != navigationGeneration) return
                Log.i(TAG, "navigation result=$result req=$reqId data=${data?.take(120)}")
                navigating = false
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                listener?.onMotionEvent("navigation_status", "completed:$result")
            }

            override fun onStatusUpdate(status: Int, data: String?) {
                if (generation == navigationGeneration) {
                    listener?.onMotionEvent("navigation_status", "status:$status:${data.orEmpty()}")
                }
            }

            override fun onError(errorCode: Int, errorString: String?) {
                if (generation != navigationGeneration) return
                Log.w(TAG, "navigation error=$errorCode req=$reqId msg=$errorString")
                navigating = false
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                listener?.onMotionEvent("navigation_status", "failed:$errorCode:${errorString.orEmpty()}")
            }
        }
        return try {
            val rc = invoke(api, reqId, actionListener)
            Log.i(TAG, "$operation req=$reqId rc=$rc")
            if (rc == 0) {
                navigating = true
                CommandOutcome.ok(operation)
            } else {
                if (generation == navigationGeneration) ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                vendorRefusal(operation, rc)
            }
        } catch (e: Throwable) {
            if (generation == navigationGeneration) ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
            exception(operation, e)
        }
    }

    override fun stopNavigation(): CommandOutcome {
        val generation = ++navigationGeneration
        navigating = false
        if (!isAvailable()) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
            return unavailable("stop_navigation")
        }
        val api = RobotApi.getInstance()
        if (!api.isApiConnectedService) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
            return disconnected("stop_navigation")
        }
        return try {
            val rc = api.stopNavigation(reqSeq.incrementAndGet().toInt())
            if (rc == 0) {
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                CommandOutcome.ok("stop_navigation")
            } else {
                ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                vendorRefusal("stop_navigation", rc)
            }
        } catch (e: Throwable) {
            ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
            exception("stop_navigation", e)
        }
    }

    fun pollPosition(): Boolean {
        return try {
        val api = RobotApi.getInstance()
        if (!api.isApiConnectedService) {
            localization = Localization.ERROR
            false
        } else {
        val pose = api.currentPose
        if (pose == null) {
            val changed = localization != Localization.UNLOCALIZED
            localization = Localization.UNLOCALIZED
            val now = System.currentTimeMillis()
            if (changed || now - lastPoseUnavailableMs > POSE_UNAVAILABLE_THROTTLE_MS) {
                lastPoseUnavailableMs = now
                listener?.onMotionEvent("pose_status", "unavailable")
            }
            false
        } else {
            val changed = localization != Localization.LOCALIZED
            localization = Localization.LOCALIZED
            if (changed) listener?.onMotionEvent("pose_status", "available")
            listener?.onPosition(pose.x.toDouble(), pose.y.toDouble(), pose.theta.toDouble())
            true
        }
        }
    } catch (e: Throwable) {
        localization = Localization.ERROR
        listener?.onMotionEvent("pose_status", "error:${e.message.orEmpty()}")
        false
    }
    }

    fun isMoving(): Boolean = moving || navigating

    private fun unavailable(op: String) = CommandOutcome.refused(op, "core_service_unavailable")
    private fun disconnected(op: String) = CommandOutcome.refused(op, "core_service_disconnected")
    private fun busy(op: String) = CommandOutcome.refused(
        op, "chassis_busy", detail = "owner=${ChassisArbiter.currentOwner()}")
    private fun vendorRefusal(op: String, rc: Int) = CommandOutcome.refused(
        op, "action_refused", rc, "vendor rejected action", "submit")
    private fun exception(op: String, e: Throwable) = CommandOutcome.refused(
        op, "call_failed", detail = e.message ?: e.javaClass.simpleName, phase = "submit")

    private companion object {
        const val TAG = "OrionMotion"
        const val POSE_UNAVAILABLE_THROTTLE_MS = 60_000L
        const val STOP_CONFIRM_TIMEOUT_MS = 1_000L
        val DIRECTIONS = setOf("forward", "backward", "left", "right")
    }
}
