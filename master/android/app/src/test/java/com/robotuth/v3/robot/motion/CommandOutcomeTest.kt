package com.robotuth.v3.robot.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandOutcomeTest {
    @Test
    fun vendorRefusalKeepsMachineReadableContext() {
        val outcome = CommandOutcome.refused(
            operation = "move",
            code = "action_refused",
            rc = -7,
            detail = "vendor rejected action",
            phase = "submit",
        )

        assertFalse(outcome.accepted)
        assertEquals(-7, outcome.rc)
        assertEquals(
            "ACTION_RESPONSE_SEND_STOP_CMD_ERROR",
            CommandOutcome.vendorSymbol(outcome.rc),
        )
        assertEquals("submit", outcome.phase)
    }

    @Test
    fun acceptedOutcomeIsExplicit() {
        val outcome = CommandOutcome.ok("stop_navigation")
        assertTrue(outcome.accepted)
        assertEquals("accepted", outcome.code)
        assertEquals("stop_navigation", outcome.operation)
    }
}
