/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.ViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.NoiseControlMode
import me.kavishdevar.librepods.presentation.components.AirPodsQuickSettingsSheet
import kotlin.io.encoding.ExperimentalEncodingApi

@RequiresApi(Build.VERSION_CODES.Q)
class AirPodsQSService : TileService() {

    private lateinit var sharedPreferences: SharedPreferences
    private var currentAncMode: Int = NoiseControlMode.OFF.ordinal + 1
    private var isAirPodsConnected: Boolean = false
    private var activeDialog: Dialog? = null

    private val ancStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsNotifications.ANC_DATA) {
                val newMode = intent.getIntExtra("data", NoiseControlMode.OFF.ordinal + 1)
                Log.d("AirPodsQSService", "Received ANC update: $newMode")
                currentAncMode = newMode
                updateTile()
            }
        }
    }

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.AIRPODS_CONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_CONNECTED")
                    isAirPodsConnected = true
                    currentAncMode =
                        ServiceManager.getService()?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)
                    updateTile()
                }
                AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_DISCONNECTED")
                    isAirPodsConnected = false
                    updateTile()
                }
            }
        }
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "off_listening_mode") {
            Log.d("AirPodsQSService", "Preference changed: $key")
            if (currentAncMode == NoiseControlMode.OFF.ordinal + 1 && !isOffModeEnabled()) {
                currentAncMode = NoiseControlMode.TRANSPARENCY.ordinal + 1
            }
            updateTile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    override fun onStartListening() {
        super.onStartListening()
        Log.d("AirPodsQSService", "onStartListening")

        val service = ServiceManager.getService()
        isAirPodsConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
        currentAncMode = service?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)

        if (currentAncMode == NoiseControlMode.OFF.ordinal + 1 && !isOffModeEnabled()) {
             currentAncMode = NoiseControlMode.TRANSPARENCY.ordinal + 1
        }

        val ancIntentFilter = IntentFilter(AirPodsNotifications.ANC_DATA)
        val availabilityIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ancStatusReceiver, ancIntentFilter, RECEIVER_EXPORTED)
                registerReceiver(availabilityReceiver, availabilityIntentFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(ancStatusReceiver, ancIntentFilter)
                registerReceiver(availabilityReceiver, availabilityIntentFilter)
            }
            sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
            Log.d("AirPodsQSService", "Receivers registered")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error registering receivers: $e")
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d("AirPodsQSService", "onStopListening")
        try {
            unregisterReceiver(ancStatusReceiver)
            unregisterReceiver(availabilityReceiver)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
            Log.d("AirPodsQSService", "Receivers unregistered")
        } catch (e: IllegalArgumentException) {
            Log.e("AirPodsQSService", "Receiver not registered or already unregistered: $e")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error unregistering receivers: $e")
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d("AirPodsQSService", "onClick - Current state: $isAirPodsConnected, Current mode: $currentAncMode")
        showQuickSettingsDialog()
    }

    private fun showQuickSettingsDialog() {
        if (activeDialog?.isShowing == true) {
            Log.d("AirPodsQSService", "Dialog already active, ignoring click")
            return
        }

        try {
            val dialog = ComponentDialog(this, R.style.Theme_AirPodsQSDialog)
            activeDialog = dialog

            val window = dialog.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.decorView.setPadding(0, 0, 0, 0)
            }

            val decorView = dialog.window?.decorView ?: return
            ViewTreeLifecycleOwner.set(decorView, dialog)
            ViewTreeSavedStateRegistryOwner.set(decorView, dialog)
            ViewTreeOnBackPressedDispatcherOwner.set(decorView, dialog)

            val viewModelStore = ViewModelStore()
            val viewModelStoreOwner = ViewModelStoreOwner { viewModelStore }
            ViewTreeViewModelStoreOwner.set(decorView, viewModelStoreOwner)

            val composeView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromContainer)
                setContent {
                    AirPodsQuickSettingsSheet(
                        service = ServiceManager.getService(),
                        onDismiss = {
                            try {
                                dialog.dismiss()
                            } catch (e: Exception) {
                                Log.e("AirPodsQSService", "Error dismissing dialog: $e")
                            }
                        }
                    )
                }
            }

            dialog.setContentView(composeView)

            dialog.setOnShowListener {
                dialog.window?.let { win ->
                    win.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT
                    )
                }
            }

            dialog.setOnDismissListener {
                Log.d("AirPodsQSService", "Dialog dismissed, cleaning up")
                viewModelStore.clear()
                if (activeDialog == dialog) {
                    activeDialog = null
                }
            }

            @Suppress("DEPRECATION")
            showDialog(dialog)
            Log.d("AirPodsQSService", "TileService.showDialog called successfully")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error showing QS dialog: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            activeDialog?.dismiss()
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error dismissing dialog on destroy: $e")
        }
        activeDialog = null
    }

    private fun cycleAncMode() {
        val service = ServiceManager.getService()
        if (service == null) {
            Log.d("AirPodsQSService", "Tile clicked (cycle mode) but service is null.")
            return
        }
        val nextMode = getNextAncMode()
        Log.d("AirPodsQSService", "Cycling ANC mode to: $nextMode")
        service.aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            nextMode
        )
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        Log.d("AirPodsQSService", "updateTile - Connected: $isAirPodsConnected, Mode: $currentAncMode")

        val deviceName = sharedPreferences.getString("name", "Airpods") ?: "Airpods"

        if (isAirPodsConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getModeLabel(currentAncMode)
            tile.subtitle = deviceName
            tile.icon = Icon.createWithResource(this, getModeIcon(currentAncMode))
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Airpods"
            tile.subtitle = "Disconnected"
            tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        }

        try {
            tile.updateTile()
            Log.d("AirPodsQSService", "Tile updated successfully")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error updating tile: $e")
        }
    }

    private fun isOffModeEnabled(): Boolean {
        return sharedPreferences.getBoolean("off_listening_mode", true)
    }

    private fun getAvailableModes(): List<Int> {
        val modes = mutableListOf(
            NoiseControlMode.TRANSPARENCY.ordinal + 1,
            NoiseControlMode.ADAPTIVE.ordinal + 1,
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1
        )
        if (isOffModeEnabled()) {
            modes.add(0, NoiseControlMode.OFF.ordinal + 1)
        }
        return modes
    }

    private fun getNextAncMode(): Int {
        val availableModes = getAvailableModes()
        Log.d("AirPodsQSService", "availableModes: $availableModes, currentAncMode: $currentAncMode")
        val currentIndex = availableModes.indexOf(currentAncMode)
        val nextIndex = (currentIndex + 1) % availableModes.size
        Log.d("AirPodsQSService", "nextIndex: $nextIndex")
        return availableModes[nextIndex]
    }

    private fun getModeLabel(mode: Int): String {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> "Off"
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> "Transparency"
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> "Adaptive"
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> "Noise Cancellation"
            else -> "Unknown"
        }
    }

     private fun getModeIcon(mode: Int): Int {
         return when (mode) {
             NoiseControlMode.OFF.ordinal + 1 -> R.drawable.noise_cancellation
             NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.transparency
             NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.adaptive
             NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.noise_cancellation
             else -> R.drawable.airpods
         }
     }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d("AirPodsQSService", "Tile added")
    }
}
