package com.cloudwhile.tscc.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cloudwhile.tscc.data.model.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioRouteController(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _availableRoutes = MutableStateFlow(listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()

    private val _activeRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    private var callActive = false
    private var callbackRegistered = false
    private var userManualRoute: AudioRoute? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val prevSet = _availableRoutes.value.toSet()
            refreshRoutes()
            if (!callActive) return
            val newlyAdded = _availableRoutes.value.filter { it !in prevSet }
            when {
                AudioRoute.BLUETOOTH in newlyAdded -> {
                    userManualRoute = null
                    applyRoute(AudioRoute.BLUETOOTH)
                }
                AudioRoute.WIRED_HEADSET in newlyAdded && _activeRoute.value != AudioRoute.BLUETOOTH -> {
                    userManualRoute = null
                    applyRoute(AudioRoute.WIRED_HEADSET)
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
            if (!callActive) return
            val currentAvailable = _availableRoutes.value
            if (_activeRoute.value !in currentAvailable) {
                userManualRoute = null
                applyPreferredDefaultRoute(currentAvailable)
            }
        }
    }

    init {
        refreshRoutes()
    }

    fun start() {
        callActive = true
        userManualRoute = null
        if (!callbackRegistered) {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
            callbackRegistered = true
        }
        refreshRoutes()
        applyPreferredDefaultRoute(_availableRoutes.value)
    }

    fun stop() {
        callActive = false
        userManualRoute = null
        val am = audioManager ?: return
        if (callbackRegistered) {
            runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
            callbackRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
            }
        }
    }

    fun reapplyActiveRoute() {
        if (!callActive) return
        refreshRoutes()
        val desired = userManualRoute?.takeIf { it in _availableRoutes.value }
            ?: _activeRoute.value.takeIf { it in _availableRoutes.value }
        if (desired != null) {
            applyRoute(desired)
        } else {
            applyPreferredDefaultRoute(_availableRoutes.value)
        }
    }

    private fun applyPreferredDefaultRoute(available: List<AudioRoute>) {
        val chosen = when {
            AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
            AudioRoute.WIRED_HEADSET in available -> AudioRoute.WIRED_HEADSET
            AudioRoute.SPEAKER in available -> AudioRoute.SPEAKER
            else -> available.firstOrNull() ?: AudioRoute.SPEAKER
        }
        applyRoute(chosen)
    }

    fun refreshRoutes() {
        val am = audioManager ?: return
        val routes = linkedSetOf<AudioRoute>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
            for (d in devices) {
                mapDeviceToRoute(d.type)?.let { routes.add(it) }
            }
        } else {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in outputs) {
                mapDeviceToRoute(d.type)?.let { routes.add(it) }
            }
        }
        if (routes.isEmpty()) {
            routes.add(AudioRoute.SPEAKER)
        }
        _availableRoutes.value = routes.toList()
        if (_activeRoute.value !in routes) {
            _activeRoute.value = routes.first()
        }
    }

    fun selectRoute(route: AudioRoute) {
        userManualRoute = route
        applyRoute(route)
    }

    private fun applyRoute(route: AudioRoute) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
            val target = devices.firstOrNull { mapDeviceToRoute(it.type) == route }
            if (target != null) {
                if (callActive) {
                    runCatching { am.setCommunicationDevice(target) }
                }
                _activeRoute.value = route
            }
        } else {
            if (callActive) {
                @Suppress("DEPRECATION")
                when (route) {
                    AudioRoute.BLUETOOTH -> {
                        am.isSpeakerphoneOn = false
                        runCatching {
                            am.startBluetoothSco()
                            am.isBluetoothScoOn = true
                        }
                    }
                    AudioRoute.SPEAKER -> {
                        runCatching {
                            am.stopBluetoothSco()
                            am.isBluetoothScoOn = false
                        }
                        am.isSpeakerphoneOn = true
                    }
                    AudioRoute.EARPIECE, AudioRoute.WIRED_HEADSET -> {
                        runCatching {
                            am.stopBluetoothSco()
                            am.isBluetoothScoOn = false
                        }
                        am.isSpeakerphoneOn = false
                    }
                }
            }
            _activeRoute.value = route
        }
    }

    private fun mapDeviceToRoute(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
        else -> null
    }
}
