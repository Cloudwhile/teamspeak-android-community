package com.cloudwhile.tscc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cloudwhile.tscc.data.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cleans up cached update APK files immediately after Android finishes an in-place package upgrade.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdateManager.purgeUpdatesCacheDir(appCtx)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
