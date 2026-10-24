package com.robotuth.v3.robot

/**
 * Single-owner lock for the physical chassis. Focus-follow, remote manual drive and
 * RobotOS navigation are mutually exclusive: whoever does not hold the arbiter must
 * not touch motion APIs. CoreService would answer `rc=-7` anyway, but refusing here
 * keeps the intent explicit and avoids stop/start ping-pong between the two features.
 */
object ChassisArbiter {
    const val OWNER_FOCUS_FOLLOW = "focus_follow"
    const val OWNER_REMOTE_MANUAL = "remote_manual"
    const val OWNER_REMOTE_NAVIGATION = "remote_navigation"
    const val OWNER_REMOTE = OWNER_REMOTE_MANUAL

    @Volatile
    private var owner: String? = null

    /** Acquire the chassis for [who]; succeeds when free or already owned by [who]. */
    @Synchronized
    fun acquire(who: String): Boolean {
        if (owner == null || owner == who) {
            owner = who
            return true
        }
        return false
    }

    /** Release only if [who] actually holds it (stale releases are ignored). */
    @Synchronized
    fun release(who: String) {
        if (owner == who) owner = null
    }

    @Synchronized
    fun isFreeOr(who: String): Boolean = owner == null || owner == who

    @Synchronized
    fun currentOwner(): String? = owner

    @Synchronized
    fun releaseRemote() {
        if (owner == OWNER_REMOTE_MANUAL || owner == OWNER_REMOTE_NAVIGATION) owner = null
    }
}
