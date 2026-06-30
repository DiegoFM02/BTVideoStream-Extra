package com.example.btvideostream.core.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Permisos necesarios según la versión de Android:
 *  - API 31+ (Android 12): BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE.
 *  - API <31: BLUETOOTH, BLUETOOTH_ADMIN y ubicación fina (requisito para escanear).
 *  - API 33+ (Android 13): POST_NOTIFICATIONS para las notificaciones push de estado.
 */
fun requiredBluetoothPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        add(Manifest.permission.BLUETOOTH)
        add(Manifest.permission.BLUETOOTH_ADMIN)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * Estado y disparador de la solicitud de permisos para usar en Compose.
 * [granted] indica si TODOS los permisos críticos de Bluetooth están concedidos.
 */
class BluetoothPermissionState internal constructor(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberBluetoothPermissionState(): BluetoothPermissionState {
    val context = LocalContext.current
    val permissions = remember { requiredBluetoothPermissions() }

    fun allGranted(): Boolean = permissions.all {
        // POST_NOTIFICATIONS no es crítico para funcionar; el resto sí.
        if (it == Manifest.permission.POST_NOTIFICATIONS) true
        else ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = allGranted() }

    return BluetoothPermissionState(
        granted = granted,
        request = { launcher.launch(permissions) }
    )
}
