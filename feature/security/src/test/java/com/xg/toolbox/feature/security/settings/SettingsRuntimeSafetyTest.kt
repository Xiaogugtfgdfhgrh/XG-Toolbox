package com.xg.toolbox.feature.security.settings

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRuntimeSafetyTest {
    @Test
    fun provisionRootDenialAndTimeoutPreserveActionableDetail() {
        val denied = XgResult.Err(
            ErrorCode.ERR_SU_DENIED,
            "Root 授权被拒绝",
            "su: permission denied",
        ).settingsDescription()
        assertTrue(denied.contains("Root 授权被拒绝"))
        assertTrue(denied.contains("permission denied"))

        val timeout = XgResult.Err(
            ErrorCode.ERR_SU_TIMEOUT,
            "Root 授权超时",
            "30s elapsed while waiting for su",
        ).settingsDescription()
        assertTrue(timeout.contains("Root 授权超时"))
        assertTrue(timeout.contains("30s elapsed"))
    }

    @Test
    fun hideProviderFailureIsShownWithConcreteClassAndMessage() {
        val message = NoSuchAlgorithmException(
            "no such algorithm: RSA for provider BC",
        ).settingsUserMessage()

        assertTrue(message.contains("no such algorithm"))
        assertTrue(message.contains("provider BC"))
    }
}
