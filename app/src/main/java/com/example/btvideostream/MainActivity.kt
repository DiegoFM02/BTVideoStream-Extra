package com.example.btvideostream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.btvideostream.core.permissions.rememberBluetoothPermissionState
import com.example.btvideostream.ui.screens.ClientScreen
import com.example.btvideostream.ui.screens.RoleSelectionScreen
import com.example.btvideostream.ui.screens.ServerScreen
import com.example.btvideostream.ui.theme.BTVideoStreamTheme
import com.example.btvideostream.ui.theme.ThemeController

/** Destinos de la navegación simple basada en estado. */
private enum class Screen { ROLE, SERVER, CLIENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val themeController = remember { ThemeController(context) }

            BTVideoStreamTheme(
                appTheme = themeController.appTheme,
                darkMode = themeController.darkMode
            ) {
                var screen by rememberSaveable { mutableStateOf(Screen.ROLE) }
                val permissions = rememberBluetoothPermissionState()

                when (screen) {
                    Screen.ROLE -> RoleSelectionScreen(
                        themeController = themeController,
                        onServerSelected = { screen = Screen.SERVER },
                        onClientSelected = { screen = Screen.CLIENT }
                    )

                    Screen.SERVER -> PermissionGate(permissions.granted, permissions.request) {
                        ServerScreen(
                            themeController = themeController,
                            onBack = { screen = Screen.ROLE }
                        )
                    }

                    Screen.CLIENT -> PermissionGate(permissions.granted, permissions.request) {
                        ClientScreen(
                            themeController = themeController,
                            onBack = { screen = Screen.ROLE }
                        )
                    }
                }
            }
        }
    }
}

/** Muestra el contenido solo si los permisos de Bluetooth están concedidos. */
@Composable
private fun PermissionGate(
    granted: Boolean,
    onRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (granted) {
        content()
    } else {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Esta función necesita permisos de Bluetooth",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Se usan para descubrir, conectar y comunicar los dos dispositivos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Button(onClick = onRequest) { Text("Conceder permisos") }
            }
        }
    }
}
