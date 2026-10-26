package com.xg.toolbox.core.rootops

/**
 * Snapshot of the user-visible root operations state.
 *
 * All booleans are based on observed command results or the app's own Android
 * component/battery state; they are not optimistic preference mirrors.
 */
data class RootOpsStatus(
    val rootGranted: Boolean = false,
    val bootReceiverEnabled: Boolean = false,
    val userUnlockReceiverEnabled: Boolean = false,
    val batteryWhitelisted: Boolean = false,
    val backgroundAppOpsAllowed: Boolean = false,
    val anyBackgroundAppOpsAllowed: Boolean = false,
    val standbyBucketActive: Boolean = false,
    val oplusStartupSynced: Boolean? = null,
    val targetUserId: Int = 0,
    val keepAliveRunning: Boolean = false,
    val provisioningReport: String = "",
    val message: String = "",
)
