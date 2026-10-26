package com.xg.toolbox.core.rootops

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGuardStopCommandTest {
    @Test
    fun stopCommandDoesNotContainFullGuardTokenInItsOwnCommandLine() {
        val command = buildGuardStopCommandFor(
            "/data/user/0/com.xg.toolbox/files/root_guard.pid",
            "com.xg.toolbox",
        )

        assertFalse(
            "stop shell cannot contain the full token or it will kill itself",
            command.contains("xg-root-guard-v1:com.xg.toolbox"),
        )
        assertTrue(command.contains("printf '%s%s' 'xg-root-guard-v1:' 'com.xg.toolbox'"))
        assertTrue(command.contains("grep -F -q \"\$XG_TOKEN\""))
        assertTrue(command.contains("echo STOPPED"))
    }
    @Test
    fun alreadyEnabledReceiverNeverIssuesAnotherPmMutation() {
        assertTrue(shouldSkipComponentMutation(queryState = true, localState = true, target = true))
        assertTrue(shouldSkipComponentMutation(queryState = null, localState = true, target = true))
        assertTrue(
            "local PackageManager enabled must win over an incomplete shell query",
            shouldSkipComponentMutation(queryState = false, localState = true, target = true),
        )
        assertFalse(shouldSkipComponentMutation(queryState = false, localState = false, target = true))
    }

    @Test
    fun disabledReceiverStillNeedsOneMutation() {
        assertFalse(shouldSkipComponentMutation(queryState = false, localState = false, target = true))
        assertTrue(shouldSkipComponentMutation(queryState = false, localState = false, target = false))
    }

}
