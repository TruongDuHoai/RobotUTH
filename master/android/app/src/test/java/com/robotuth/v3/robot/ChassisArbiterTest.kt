package com.robotuth.v3.robot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChassisArbiterTest {
    @After
    fun cleanUp() {
        ChassisArbiter.release(ChassisArbiter.OWNER_FOCUS_FOLLOW)
        ChassisArbiter.releaseRemote()
    }

    @Test
    fun acquireIsExclusiveAndReentrantForSameOwner() {
        assertTrue(ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_MANUAL))
        assertTrue(ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_MANUAL))
        assertFalse(ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_NAVIGATION))
        assertEquals(ChassisArbiter.OWNER_REMOTE_MANUAL, ChassisArbiter.currentOwner())
    }

    @Test
    fun releaseRemoteClearsEitherRemoteModeButNotFocusFollow() {
        assertTrue(ChassisArbiter.acquire(ChassisArbiter.OWNER_REMOTE_NAVIGATION))
        ChassisArbiter.releaseRemote()
        assertNull(ChassisArbiter.currentOwner())

        assertTrue(ChassisArbiter.acquire(ChassisArbiter.OWNER_FOCUS_FOLLOW))
        ChassisArbiter.releaseRemote()
        assertEquals(ChassisArbiter.OWNER_FOCUS_FOLLOW, ChassisArbiter.currentOwner())
    }
}
