package com.xg.toolbox.core.shell

import com.xg.toolbox.core.common.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ShellErrorsTest {
    @Test
    fun rootDenialIsMappedToExplicitDeniedCode() {
        assertEquals(ErrorCode.ERR_SU_DENIED, ShellErrors.normalize("su: permission denied"))
        assertEquals(DenyReason.USER_REJECTED, ShellErrors.toDenyReason(ErrorCode.ERR_SU_DENIED))
    }

    @Test
    fun rootTimeoutIsMappedToTimeoutReason() {
        assertEquals(DenyReason.TIMEOUT, ShellErrors.toDenyReason(ErrorCode.ERR_SU_TIMEOUT))
        assertNull(ShellErrors.normalize(""))
    }

    @Test
    fun selinuxAndPolicyErrorsAreNotReportedAsSuccess() {
        assertEquals(ErrorCode.ERR_SELINUX, ShellErrors.normalize("avc: denied { write }"))
        assertEquals(
            ErrorCode.ERR_POLICY_BLOCKED,
            ShellErrors.normalize("SecurityException: policy blocks command"),
        )
        assertFalse(ShellErrors.isSuLevelDenial("ordinary output"))
    }
}
