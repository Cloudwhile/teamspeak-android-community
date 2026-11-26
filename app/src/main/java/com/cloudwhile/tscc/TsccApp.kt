package com.cloudwhile.tscc

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.cloudwhile.tscc.data.SettingsRepository
import com.cloudwhile.tscc.data.TsccRepository
import com.cloudwhile.tscc.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TsccApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var tsccRepository: TsccRepository
        private set

    private var startedActivityCount = 0
    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerForegroundTracker()
        createNotificationChannels()
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        tsccRepository = TsccRepository(this, database, settingsRepository)
    }

    private fun registerForegroundTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount = (startedActivityCount + 1).coerceAtLeast(1)
                _isAppInForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                _isAppInForeground.value = startedActivityCount > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val voiceChannel = NotificationChannel(
            CHANNEL_VOICE_CALL,
            getString(R.string.notif_channel_voice_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_voice_desc)
            setShowBadge(false)
        }
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alerts_desc)
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(voiceChannel, alertsChannel))
    }

    companion object {
        const val CHANNEL_VOICE_CALL = "tscc_voice_call_channel"
        const val CHANNEL_ALERTS = "tscc_alerts_channel"

        lateinit var instance: TsccApp
            private set
    }
}
