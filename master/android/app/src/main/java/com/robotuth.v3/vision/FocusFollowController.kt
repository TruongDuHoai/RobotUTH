package com.robotuth.v3.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.SettingsUtil
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.person.PersonApi
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.ChassisArbiter
import com.robotuth.v3.stt.audio.OrionHomeCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orion "visual ability" face focus-follow — lets the robot turn its head/body to
 * keep the person in front of it in view while RobotUTH is in the foreground.
 *
 * Doc: https://doc.orionstar.com/en/knowledge-base/visual-ability/
 * Verified against `com.orionstar.robot:core:12.2.1`
 * (`com.ainirobot.coreservice.client.RobotApi`).
 *
 * Rules that actually matter on device:
 *  - Never forced: if CoreService is not installed (phone, emulator) every method is a no-op.
 *  - Focus follow **owns the chassis** — navigation/guidance must not run at the same time.
 *  - Do not re-issue start for the same target; stop first, then start again (vendor doc).
 *  - `ACTION_RESPONSE_ALREADY_RUN` / `..._REQUEST_RES_ERROR` mean "a follow or chassis action
 *    is already running" → one stop-then-retry, then give up until the next app start.
 *  - Once a request was accepted, the chassis is held even if no lock ever arrived, so [stop]
 *    keys off the live request id rather than the "locked" flag.
 *  - Smart follow and face follow are separate vendor actions: stop the one that was started
 *    (`stopSmartFocusFollow` vs `stopFocusFollow`), otherwise the other keeps running.
 *  - Orion marks this ability `@Deprecated` (legal reasons) yet these remain the documented
 *    calls, so the deprecation warnings are suppressed for the whole class.
 *  - This class is also the app's only CoreService connection, so it owns the nav-stack health
 *    sweep ([sweepNaviHealth]) that diagnoses and clears the stuck obstacle latch which otherwise
 *    denies us the chassis. Tuning lives in `AppConfig.NAVI_HEALTH_*`.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // vendor deprecates the entire visual ability
class FocusFollowController(private val context: Context) {

    /** False on phones/emulators and when the [AppConfig] kill switch is off. */
    val isSupported: Boolean =
        AppConfig.FOCUS_FOLLOW_ENABLED && OrionHomeCompat.hasCoreService(context)

    private val handler = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    private val following = AtomicBoolean(false)
    private val rearmPending = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)
    private val naviSpeedApplied = AtomicBoolean(false)
    private val naviProbePending = AtomicBoolean(false)

    @Volatile private var activeReqId = 0
    @Volatile private var smartFollow = false
    @Volatile private var reqIdSeq = 1000
    @Volatile private var prevLineSpeed: String? = null

    // RobotApi.setLineSpeed needs a CommandListener; we only care about the log lines.
    private val speedListener = object : CommandListener() {
        override fun onResult(result: Int, data: String?) {
            Log.d(TAG, "setLineSpeed result=$result data=${data?.take(80)}")
        }

        override fun onError(errorCode: Int, errorString: String?) {
            Log.w(TAG, "setLineSpeed error code=$errorCode msg=$errorString")
        }
    }

    private val rearmRunnable = Runnable {
        rearmPending.set(false)
        beginFollow()
    }

    private val beginRunnable = Runnable { beginFollow() }

    private val naviProbeRunnable = Runnable {
        naviProbePending.set(false)
        sweepNaviHealth()
    }

    private val apiListener = object : ApiListener {
        override fun handleApiDisabled() {
            connected.set(false)
            Log.w(TAG, "CoreService API disabled — focus follow stays off")
        }

        override fun handleApiConnected() {
            connected.set(true)
            Log.i(TAG, "CoreService connected")
            postBegin()
            // CoreService (re)connected — the nav module may have been restarted underneath us by
            // the vendor watchdog, so re-check its state instead of trusting the last session.
            scheduleNaviProbe()
        }

        override fun handleApiDisconnected() {
            connected.set(false)
            following.set(false)
            activeReqId = 0
            smartFollow = false
            naviSpeedApplied.set(false)
            prevLineSpeed = null
            // Binder gone → the chassis we held is gone with it.
            ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
            // The watchdog restarting the nav module lands here — make the next connection probe.
            handler.removeCallbacks(naviProbeRunnable)
            naviProbePending.set(false)
            Log.i(TAG, "CoreService disconnected")
        }
    }

    private val actionListener = object : ActionListener() {
        override fun onResult(result: Int, data: String?) {
            Log.d(TAG, "onResult result=$result data=${data?.take(120)}")
        }

        override fun onStatusUpdate(status: Int, data: String?) {
            when (status) {
                Definition.STATUS_TRACK_TARGET_SUCCEED -> {
                    following.set(true)
                    Log.i(TAG, "focus follow locked (req=$activeReqId)")
                }
                Definition.STATUS_GUEST_APPEAR ->
                    Log.d(TAG, "target back in range")
                Definition.STATUS_GUEST_FARAWAY ->
                    Log.i(TAG, "target farther than ${AppConfig.FOCUS_FOLLOW_MAX_DISTANCE_M} m")
                Definition.STATUS_GUEST_LOST,
                Definition.STATUS_GUEST_STOP_FOLLOW -> {
                    Log.i(TAG, "target lost (status=$status) — re-arming")
                    following.set(false)
                    scheduleRearm()
                }
                else -> Log.d(TAG, "focus follow status=$status data=${data?.take(80)}")
            }
        }

        override fun onError(errorCode: Int, errorString: String?) {
            Log.w(TAG, "focus follow error code=$errorCode msg=$errorString")
            when (errorCode) {
                Definition.ERROR_TARGET_NOT_FOUND,
                Definition.ERROR_SET_TRACK_FAILED -> {
                    following.set(false)
                    scheduleRearm()
                }
            }
        }
    }

    /**
     * Called on app start. Connects to CoreService if needed and asks Orion to follow
     * whoever is in front of it. Safe to call repeatedly.
     */
    fun start() {
        if (!isSupported) {
            Log.i(TAG, "focus follow skipped — CoreService not present on this device")
            return
        }
        // New foreground session gets its own retry budget.
        restartPending.set(false)
        following.set(false)
        // …and its own navigation-health sweep, even if the binder never dropped.
        naviProbePending.set(false)
        try {
            val api = RobotApi.getInstance()
            if (connected.get() || api.isApiConnectedService) {
                connected.set(true)
                postBegin()
                scheduleNaviProbe()
                return
            }
            api.connectServer(context.applicationContext, apiListener)
            Log.i(TAG, "connecting CoreService for focus follow…")
        } catch (e: Throwable) {
            // Vendor binder can throw anything (SecurityException, DeadObjectException,
            // NoSuchMethodError on old firmware). Never take the app down with it.
            Log.w(TAG, "focus follow unavailable: ${e.message}", e)
        }
    }

    /** Release the chassis. Called when the app leaves the foreground. */
    fun stop() {
        handler.removeCallbacks(rearmRunnable)
        handler.removeCallbacks(beginRunnable)
        handler.removeCallbacks(naviProbeRunnable)
        rearmPending.set(false)
        restartPending.set(false)
        if (!isSupported) return
        restoreNaviSpeed()
        val reqId = activeReqId
        val wasSmart = smartFollow
        activeReqId = 0
        smartFollow = false
        following.set(false)
        if (reqId == 0) {
            Log.d(TAG, "stop: nothing was requested — chassis not held")
            return
        }
        try {
            val api = RobotApi.getInstance()
            val rc = if (wasSmart) api.stopSmartFocusFollow(reqId) else api.stopFocusFollow(reqId)
            Log.i(TAG, "stop ${if (wasSmart) "smart" else "face"} follow req=$reqId rc=$rc")
            ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
        } catch (e: Throwable) {
            Log.w(TAG, "stopFocusFollow failed: ${e.message}")
            ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
        }
    }

    /** Drop the CoreService binding. Called from onDestroy only. */
    fun release() {
        stop()
        if (!isSupported) return
        try {
            RobotApi.getInstance().disconnectApi()
        } catch (e: Throwable) {
            Log.d(TAG, "disconnectApi: ${e.message}")
        }
        connected.set(false)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun postBegin() {
        handler.removeCallbacks(rearmRunnable)
        handler.removeCallbacks(beginRunnable)
        rearmPending.set(false)
        handler.post(beginRunnable)
    }

    private fun beginFollow() {
        if (!isSupported || following.get()) return
        if (!connected.get()) return
        applyNaviSpeedOnce()
        val reqId = ++reqIdSeq
        val faceId = nearestFaceId()
        val smart = faceId < 0
        val rc = try {
            val api = RobotApi.getInstance()
            if (!smart) {
                // Documented path: follow this specific detected face.
                api.startFocusFollow(
                    reqId,
                    faceId,
                    AppConfig.FOCUS_FOLLOW_LOST_TIMEOUT_MS,
                    AppConfig.FOCUS_FOLLOW_MAX_DISTANCE_M,
                    actionListener,
                )
            } else {
                // Nobody face-complete yet — let Orion latch onto the nearest person itself.
                api.startSmartFocusFollow(
                    reqId,
                    AppConfig.FOCUS_FOLLOW_LOST_TIMEOUT_MS,
                    AppConfig.FOCUS_FOLLOW_MAX_DISTANCE_M,
                    actionListener,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startFocusFollow failed: ${e.message}", e)
            return
        }

        when (rc) {
            Definition.ACTION_RESPONSE_SUCCESS -> {
                activeReqId = reqId
                smartFollow = smart
                // Accepted requests hold the chassis even before any lock arrives —
                // claim it so the robot link refuses remote drive while we follow.
                ChassisArbiter.acquire(ChassisArbiter.OWNER_FOCUS_FOLLOW)
                Log.i(TAG, "${if (smart) "smart" else "face"} follow requested req=$reqId " +
                    "faceId=$faceId " +
                    "lost=${AppConfig.FOCUS_FOLLOW_LOST_TIMEOUT_MS}ms " +
                    "max=${AppConfig.FOCUS_FOLLOW_MAX_DISTANCE_M}m")
            }
            Definition.ACTION_RESPONSE_ALREADY_RUN,
            Definition.ACTION_RESPONSE_REQUEST_RES_ERROR -> restartOnce()
            Definition.ACTION_RESPONSE_SEND_STOP_CMD_ERROR -> {
                // CoreService could not stop whatever owns the chassis right now. Seen on device
                // while the lidar reports an obstacle ~20 cm ahead (nav stack logs "in dangerous")
                // or while the stock agent is driving. One bounded retry, never a loop.
                Log.w(TAG, "focus follow blocked: chassis busy/blocked (rc=-7) — retrying once")
                restartOnce()
            }
            Definition.ACTION_RESPONSE_NOT_SUPPORT_ACTION ->
                Log.w(TAG, "firmware has no focus-follow support (rc=$rc) — disabling")
            else -> Log.w(TAG, "focus follow rejected rc=$rc faceId=$faceId")
        }
    }

    /** Vendor says: stop everything, then start again. Do it once, not in a loop. */
    private fun restartOnce() {
        if (!restartPending.compareAndSet(false, true)) return
        val stopId = activeReqId
        if (stopId != 0) {
            val wasSmart = smartFollow
            activeReqId = 0
            smartFollow = false
            try {
                val api = RobotApi.getInstance()
                val rc = if (wasSmart) api.stopSmartFocusFollow(stopId) else api.stopFocusFollow(stopId)
                Log.i(TAG, "focus follow busy — stopped own ${if (wasSmart) "smart" else "face"} " +
                    "req=$stopId rc=$rc, retrying once")
                ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
            } catch (e: Throwable) {
                Log.w(TAG, "restart stop failed: ${e.message}")
                ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
            }
        } else {
            Log.i(TAG, "focus follow busy elsewhere — retrying once without stopping")
        }
        following.set(false)
        scheduleRearm()
    }

    private fun scheduleRearm() {
        if (!isSupported) return
        if (!rearmPending.compareAndSet(false, true)) return
        handler.postDelayed(rearmRunnable, AppConfig.FOCUS_FOLLOW_REARM_DELAY_MS)
    }

    /**
     * Lower the chassis navigation speed once before focus-follow starts driving, so the
     * body-turn toward a face is not so abrupt. Remembers the robot's own grade so
     * [restoreNaviSpeed] can put it back. No-op when the grade is blank or already applied.
     */
    private fun applyNaviSpeedOnce() {
        val grade = AppConfig.FOCUS_FOLLOW_NAVI_SPEED
        if (grade.isBlank()) return
        if (!naviSpeedApplied.compareAndSet(false, true)) return
        try {
            val api = RobotApi.getInstance()
            prevLineSpeed = api.getLineSpeed()
            val rc = api.setLineSpeed(++reqIdSeq, grade, speedListener)
            Log.i(TAG, "focus-follow navi speed → '$grade' (was '${prevLineSpeed ?: "unknown"}') rc=$rc")
        } catch (e: Throwable) {
            naviSpeedApplied.set(false)
            Log.w(TAG, "applyNaviSpeed failed: ${e.message}")
        }
    }

    /** Put the robot's own navigation speed back after focus-follow stops driving. */
    private fun restoreNaviSpeed() {
        if (!naviSpeedApplied.compareAndSet(true, false)) return
        val prev = prevLineSpeed
        prevLineSpeed = null
        if (prev.isNullOrBlank()) {
            Log.d(TAG, "navi speed: nothing to restore (previous grade unknown)")
            return
        }
        try {
            val rc = RobotApi.getInstance().setLineSpeed(++reqIdSeq, prev, speedListener)
            Log.i(TAG, "restore navi speed → '$prev' rc=$rc")
        } catch (e: Throwable) {
            Log.w(TAG, "restoreNaviSpeed failed: ${e.message}")
        }
    }

    /** Queue one sweep for this connection. Cheap to call; deduped by [naviProbePending]. */
    private fun scheduleNaviProbe() {
        if (!isSupported || !AppConfig.NAVI_HEALTH_PROBE_ENABLED) return
        if (!naviProbePending.compareAndSet(false, true)) return
        handler.postDelayed(naviProbeRunnable, AppConfig.NAVI_HEALTH_PROBE_DELAY_MS)
    }

    /**
     * Ask the nav stack what it currently believes, and undo the one state that we know can
     * wedge it.
     *
     * Why this exists: the endless obstacle alert on OS-R-SG04 was **not** an audio cue we could
     * mute — the SDK has no tone/buzzer call at all, the alert is driven from the nav/CAN side,
     * and Android's alarm/notification streams were already at zero. What actually silenced it
     * was clearing a *stuck* `DynamicStoppingPolicy` latch (`in dangerous laserToRobot=0.325` with
     * nothing in front of the robot) by restarting `com.ainirobot.navigationservice`; the vendor
     * `ExternalServiceWatchdog` brings that service back within ~1 s, so an adb one-liner is not
     * permanent. Re-running this sweep on every CoreService connection is what makes the fix
     * survive the watchdog.
     *
     * Everything here is read-only except the guarded `stopNavigation`, which is the same call
     * the stock stop button makes and only fires when the nav core claims it is navigating while
     * this kiosk never asked it to.
     */
    private fun sweepNaviHealth() {
        if (!isSupported || !connected.get()) return
        dumpVendorSettings()
        val api = try {
            RobotApi.getInstance()
        } catch (e: Throwable) {
            Log.w(TAG, "navi probe skipped: ${e.message}")
            return
        }
        try {
            val rc = api.getNavigationConfig(++reqIdSeq, probeListener("nav_config"))
            Log.d(TAG, "navi probe nav_config requested rc=$rc")
        } catch (e: Throwable) {
            Log.w(TAG, "navi probe nav_config failed: ${e.message}")
        }
        try {
            val rc = api.isInNavigation(++reqIdSeq, object : CommandListener() {
                override fun onResult(result: Int, data: String?) {
                    val navigating = parseNavigating(data)
                    Log.i(TAG, "navi probe is_in_navigation rc=$result data=${data?.take(120)} " +
                        "→ navigating=$navigating")
                    if (navigating == true && AppConfig.NAVI_HEALTH_CLEAR_STALE_GOAL) {
                        // Binder callbacks are not on our thread, and reqIdSeq must stay monotonic
                        // with the follow requests — so clear the goal on the handler.
                        handler.post { clearStaleGoal() }
                    }
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    Log.w(TAG, "navi probe is_in_navigation error code=$errorCode msg=$errorString")
                }
            })
            Log.d(TAG, "navi probe is_in_navigation requested rc=$rc")
        } catch (e: Throwable) {
            Log.w(TAG, "navi probe is_in_navigation failed: ${e.message}")
        }
    }

    /** The persisted vendor settings that decide when the nav stack cries "dangerous". */
    private fun dumpVendorSettings() {
        for (key in NAVI_SETTING_KEYS) {
            val value = try {
                SettingsUtil.getString(context.applicationContext, key) ?: "(unset)"
            } catch (e: Throwable) {
                "‹${e.javaClass.simpleName}›"
            }
            Log.i(TAG, "navi setting $key=$value")
        }
    }

    private fun clearStaleGoal() {
        if (!connected.get()) return
        try {
            val rc = RobotApi.getInstance().stopNavigation(++reqIdSeq)
            Log.w(TAG, "stale navigation goal cleared: stopNavigation rc=$rc")
        } catch (e: Throwable) {
            Log.w(TAG, "stopNavigation failed: ${e.message}")
        }
    }

    private fun probeListener(label: String) = object : CommandListener() {
        override fun onResult(result: Int, data: String?) {
            Log.i(TAG, "navi probe $label rc=$result data=${data?.take(400)}")
        }

        override fun onError(errorCode: Int, errorString: String?) {
            Log.w(TAG, "navi probe $label error code=$errorCode msg=$errorString")
        }
    }

    /**
     * The exact payload behind `is_in_navigation` is firmware dependent, so recognise only the
     * unambiguous forms. An unknown answer must never be read as "navigating", otherwise the
     * sweep could stop a goal somebody else legitimately asked for.
     */
    private fun parseNavigating(data: String?): Boolean? {
        val s = data?.trim()?.lowercase()?.replace(" ", "") ?: return null
        if (s.isEmpty()) return null
        if (s == "true" || s == "1") return true
        if (s == "false" || s == "0") return false
        for (key in NAVIGATING_JSON_KEYS) {
            if (s.contains("\"${key}\":true") || s.contains("\"${key}\":1")) return true
            if (s.contains("\"${key}\":false") || s.contains("\"${key}\":0")) return false
        }
        return null
    }

    /** Nearest complete face currently seen by Orion, or -1 when there is none. */
    private fun nearestFaceId(): Int = try {
        val personApi = PersonApi.getInstance()
        val faces = personApi.completeFaceList ?: personApi.allFaceList
        faces?.asSequence()
            ?.filter { it != null && it.id >= 0 && it.isWithFace }
            ?.minByOrNull { it.distance }
            ?.id
            ?: -1
    } catch (e: Throwable) {
        Log.d(TAG, "person list unavailable yet: ${e.message}")
        -1
    }

    companion object {
        private const val TAG = "FocusFollow"

        /**
         * Persisted in CoreService's own settings store (`content://settings/global`, written by
         * the vendor Settings UI), so these survive a navigationservice restart *and* a reboot —
         * they are the real answer to "make the alert stop permanently". Read-only for us:
         * `SettingsUtil.putX` is gated on a vendor package whitelist.
         */
        private val NAVI_SETTING_KEYS = listOf(
            Definition.ROBOT_OBSTACLES_AVOID,
            Definition.ROBOT_PREVENT_COLLISION,
            Definition.ROBOT_SETTINGS_OBSTACLES_AVOID_DISTANCE,
            Definition.ROBOT_FOCUS_FOLLOW,
            SettingsUtil.ROBOT_FOCUS_FOLLOW_ANGLE,
        )

        /** Field names `is_in_navigation` has been seen to answer with. */
        private val NAVIGATING_JSON_KEYS = listOf(
            "isinnavigation", "is_in_navigation", "navigation", "result", "status", "data", "value",
        )
    }
}
