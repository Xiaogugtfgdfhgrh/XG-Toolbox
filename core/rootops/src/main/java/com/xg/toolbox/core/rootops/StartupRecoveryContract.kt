package com.xg.toolbox.core.rootops

import android.content.Context
import android.content.Intent

object StartupRecoveryContract {
    const val ACTION_RECOVER_BACKGROUND_FEATURES =
        "com.xg.toolbox.action.RECOVER_BACKGROUND_FEATURES"
    const val RECEIVER_CLASS = "com.xg.toolbox.startup.StartupRestoreReceiver"

    fun request(context: Context, source: String) {
        val intent = Intent(ACTION_RECOVER_BACKGROUND_FEATURES)
            .setClassName(context.packageName, RECEIVER_CLASS)
            .setPackage(context.packageName)
            .putExtra("source", source)
        context.sendBroadcast(intent)
    }
}
