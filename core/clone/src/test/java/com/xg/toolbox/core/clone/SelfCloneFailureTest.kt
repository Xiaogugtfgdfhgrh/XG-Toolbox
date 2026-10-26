package com.xg.toolbox.core.clone

import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCloneFailureTest {
    @Test
    fun signerProviderFailureIsSurfacedWithClassAndMessage() {
        val detail = cloneThrowableDetail(
            NoSuchAlgorithmException("no such algorithm: RSA for provider BC"),
        )
        assertTrue(detail.contains("NoSuchAlgorithmException"))
        assertTrue(detail.contains("no such algorithm: RSA for provider BC"))
    }

    @Test
    fun unknownFailureWithoutMessageStillHasStableClassIdentity() {
        val detail = cloneThrowableDetail(IllegalStateException())
        assertTrue(detail.contains("IllegalStateException"))
    }
}
