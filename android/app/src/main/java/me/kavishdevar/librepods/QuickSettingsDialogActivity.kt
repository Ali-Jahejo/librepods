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

package me.kavishdevar.librepods

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import me.kavishdevar.librepods.presentation.components.AirPodsQuickSettingsSheet
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager

class QuickSettingsDialogActivity : ComponentActivity() {

    private var airPodsService: AirPodsService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as? AirPodsService.LocalBinder
            airPodsService = binder?.getService()
            isBound = true
            Log.d("QSActivity", "Service bound")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            airPodsService = null
            Log.d("QSActivity", "Service unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Intent(this, AirPodsService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }

        setContent {
            AirPodsQuickSettingsSheet(
                service = airPodsService ?: ServiceManager.getService(),
                onDismiss = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.e("QSActivity", "Error unbinding service: $e")
            }
            isBound = false
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/**
 * Backward compatibility alias for any caller expecting QuickSettingsBottomSheet.
 */
@Composable
fun QuickSettingsBottomSheet(
    service: AirPodsService?,
    onDismiss: () -> Unit
) {
    AirPodsQuickSettingsSheet(
        service = service,
        onDismiss = onDismiss
    )
}
