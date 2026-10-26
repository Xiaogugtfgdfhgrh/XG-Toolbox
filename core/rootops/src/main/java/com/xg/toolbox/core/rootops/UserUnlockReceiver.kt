package com.xg.toolbox.core.rootops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xg.toolbox.core.data.BehaviorPreferences
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UserUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_UNLOCKED) return
        val enabled = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(BehaviorPreferences.BOOT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(BehaviorPreferences.KEY_AUTOSTART, false)
        if (!enabled) {
            Log.i(TAG, "USER_UNLOCKED ignored: autostart disabled")
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = runCatching {
                    RootGuardService.start(context, bootRecovery = true)
                    StartupRecoveryContract.request(context, "user-unlocked")
                }
                val journal = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BootRecoveryEntryPoint::class.java,
                ).startupRecoveryJournal()
                val failure = result.exceptionOrNull()
                journal.record(
                    "user-unlocked",
                    result.isSuccess,
                    if (failure == null) {
                        "已请求后台守护与功能恢复"
                    } else {
                        failure.message ?: failure.javaClass.simpleName
                    },
                )
                if (result.isFailure) {
                    Log.e(TAG, "USER_UNLOCKED recovery failed", result.exceptionOrNull())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "USER_UNLOCKED dispatch failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "XgUserUnlock"
    }
}
