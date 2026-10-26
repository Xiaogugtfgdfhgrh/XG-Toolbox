package com.xg.toolbox.core.rootops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.xg.toolbox.core.data.BehaviorPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return
        if (!autostartEnabled(context)) {
            Log.i(TAG, "boot action=$action ignored: autostart disabled")
            return
        }
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.i(TAG, "boot action=$action waiting for USER_UNLOCKED")
            runCatching { journal(context).record("boot", false, "action=$action 等待用户解锁，由 UserUnlockReceiver 继续") }
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = runCatching {
                    RootGuardService.start(context, bootRecovery = true)
                    StartupRecoveryContract.request(context, "boot:$action")
                }
                val journal = journal(context)
                if (result.isSuccess) {
                    journal.record("boot", true, "action=$action 已请求后台守护与功能恢复")
                    Log.i(TAG, "boot action=$action requested background recovery")
                } else {
                    val error = result.exceptionOrNull()
                    journal.record("boot", false, error?.message ?: error?.javaClass?.simpleName.orEmpty())
                    Log.e(TAG, "boot action=$action failed", error)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "boot action=$action dispatch failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun journal(context: Context): StartupRecoveryJournal =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootRecoveryEntryPoint::class.java,
        ).startupRecoveryJournal()

    private fun autostartEnabled(context: Context): Boolean =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(BehaviorPreferences.BOOT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(BehaviorPreferences.KEY_AUTOSTART, false)

    private companion object {
        const val TAG = "XgBootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootRecoveryEntryPoint {
    fun startupRecoveryJournal(): StartupRecoveryJournal
}
