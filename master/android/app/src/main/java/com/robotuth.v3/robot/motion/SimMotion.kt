package com.robotuth.v3.robot.motion

import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.ChassisArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot

class SimMotion : RobotMotion {
    override var listener: RobotMotion.Listener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var v = 0f
    private var w = 0f
    private var x = 0.0
    private var y = 0.0
    private var theta = 0.0
    @Volatile private var lastRefreshMs = 0L
    @Volatile private var navTarget: Triple<Double, Double, Double>? = null
    private val places = mapOf(
        "PhongDaoTao" to Triple(2.0, 1.0, 0.0),
        "SinhVien" to Triple(0.0, 0.0, 0.0),
    )

    override fun isAvailable() = true

    override fun move(direction: String, speed: Float): CommandOutcome {
        if (!ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_MANUAL)) return busy("move")
        val linear = speed.coerceIn(-AppConfig.ROBOT_LINK_MAX_LINEAR, AppConfig.ROBOT_LINK_MAX_LINEAR)
        val angular = (Math.abs(linear) / AppConfig.ROBOT_LINK_MAX_LINEAR *
            AppConfig.ROBOT_LINK_MAX_ANGULAR).coerceIn(0f, AppConfig.ROBOT_LINK_MAX_ANGULAR)
        synchronized(lock) {
            when (direction) {
                "forward" -> { v = linear; w = 0f }
                "backward" -> { v = -linear; w = 0f }
                "left" -> { v = 0f; w = angular }
                "right" -> { v = 0f; w = -angular }
                else -> {
                    ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                    return CommandOutcome.refused("move", "invalid_direction", detail = direction)
                }
            }
        }
        lastRefreshMs = System.currentTimeMillis()
        ensureLoop()
        return CommandOutcome.ok("move")
    }

    override fun stop(): CommandOutcome {
        synchronized(lock) { v = 0f; w = 0f; navTarget = null }
        ChassisArbiter.releaseRemote()
        return CommandOutcome.ok("emergency_stop")
    }

    override fun navigate(destination: String): CommandOutcome {
        val target = places[destination]
            ?: return CommandOutcome.refused("navigate", "invalid_destination", detail = destination)
        return beginNavigation("navigate", target, destination)
    }

    override fun driveTo(x: Double, y: Double, theta: Double): CommandOutcome {
        if (!x.isFinite() || !y.isFinite() || !theta.isFinite()) {
            return CommandOutcome.refused("drive_to", "invalid_destination")
        }
        return beginNavigation("drive_to", Triple(x, y, theta), "($x, $y)")
    }

    private fun beginNavigation(
        operation: String,
        target: Triple<Double, Double, Double>,
        label: String,
    ): CommandOutcome {
        if (!ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_NAVIGATION)) return busy(operation)
        synchronized(lock) { navTarget = target }
        listener?.onMotionEvent("navigation_status", "started:$label")
        ensureLoop()
        return CommandOutcome.ok(operation)
    }

    override fun stopNavigation(): CommandOutcome {
        synchronized(lock) { navTarget = null; v = 0f; w = 0f }
        ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
        return CommandOutcome.ok("stop_navigation")
    }

    private fun ensureLoop() {
        if (started.compareAndSet(false, true)) scope.launch { loop() }
    }

    private suspend fun loop() {
        var lastEmit = 0L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val dt = 0.1
            val moving = synchronized(lock) {
                val target = navTarget
                if (target != null) {
                    val dx = target.first - x
                    val dy = target.second - y
                    val dist = hypot(dx, dy)
                    if (dist < 0.05) {
                        theta = target.third
                        v = 0f; w = 0f; navTarget = null
                        ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_NAVIGATION)
                        listener?.onMotionEvent("navigation_status", "completed")
                    } else {
                        val want = atan2(dy, dx)
                        w = (want - theta).toFloat().coerceIn(
                            -AppConfig.ROBOT_LINK_MAX_ANGULAR,
                            AppConfig.ROBOT_LINK_MAX_ANGULAR,
                        )
                        v = if (kotlin.math.abs(want - theta) > 0.5) 0f else minOf(0.2f, dist.toFloat())
                    }
                } else if ((v != 0f || w != 0f) &&
                    now - lastRefreshMs > AppConfig.ROBOT_LINK_WATCHDOG_MS) {
                    v = 0f; w = 0f
                    ChassisArbiter.release(ChassisArbiter.OWNER_REMOTE_MANUAL)
                    listener?.onMotionEvent("error", "watchdog_stop")
                }
                theta += w * dt
                x += v * kotlin.math.cos(theta) * dt
                y += v * kotlin.math.sin(theta) * dt
                v != 0f || w != 0f || navTarget != null
            }
            val interval = if (moving) 200L else 1000L
            if (now - lastEmit >= interval) {
                lastEmit = now
                val pose = synchronized(lock) { Triple(x, y, theta) }
                listener?.onPosition(pose.first, pose.second, pose.third)
            }
            delay(100)
        }
    }

    private fun busy(op: String) = CommandOutcome.refused(
        op, "chassis_busy", detail = "owner=${ChassisArbiter.currentOwner()}")
}
