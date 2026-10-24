package com.robotuth.v3.robot.motion

/**
 * Motion seam between the robot link (WebSocket commands) and the chassis.
 *
 * Two implementations:
 *  - [OrionMotion] — real CoreService calls (`com.ainirobot.coreservice.client.RobotApi`).
 *  - [SimMotion]   — in-app virtual chassis, so the whole link can be exercised
 *                    on phones/emulators/CI without a robot.
 *
 * Manual motion is subject to the link's dead-man watchdog: a move() must be
 * refreshed within `ROBOT_LINK_WATCHDOG_MS` or the implementation stops locally.
 */
interface RobotMotion {

    interface Listener {
        fun onPosition(x: Double, y: Double, theta: Double)

        /** type: "navigation_status" | "error"; data is a short human-readable string. */
        fun onMotionEvent(type: String, data: String)
    }

    var listener: Listener?

    /** False when this implementation cannot drive anything on this device. */
    fun isAvailable(): Boolean

    /**
     * Start (or refresh) manual motion. `direction` ∈ forward|backward|left|right;
     * speed is clamped locally — never trust the value from the network.
     * Returns false when refused (chassis busy, unsupported, bad direction).
     */
    fun move(direction: String, speed: Float): CommandOutcome

    /** Immediate stop; also cancels the manual watchdog. */
    fun stop(): CommandOutcome

    /** Navigate to a RobotOS saved place by name (no custom path planning). */
    fun navigate(destination: String): CommandOutcome

    /**
     * Navigate to raw map coordinates (console tap-to-drive). Coordinates are in
     * the map frame the robot was localized in; refused when unlocalized.
     */
    fun driveTo(x: Double, y: Double, theta: Double): CommandOutcome

    fun stopNavigation(): CommandOutcome

    /**
     * Localization state for readiness snapshots: "localized", "unlocalized",
     * "unknown" or "error". The simulated chassis is always localized.
     */
    fun localizationState(): String = "localized"
}
