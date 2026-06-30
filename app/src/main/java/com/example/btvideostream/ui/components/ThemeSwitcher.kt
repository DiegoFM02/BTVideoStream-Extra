package com.example.btvideostream.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.example.btvideostream.ui.theme.AppTheme
import com.example.btvideostream.ui.theme.DarkMode
import com.example.btvideostream.ui.theme.ThemeController

/**
 * Controles para alternar entre los temas Guinda/Azul y el modo claro/oscuro.
 * Pensado para colocarse en las acciones de una TopAppBar.
 */
@Composable
fun ThemeSwitcher(controller: ThemeController) {
    Row {
        // Botón rápido claro/oscuro (alternancia manual exigida por el examen)
        IconButton(onClick = { controller.toggleDark() }) {
            val isDark = controller.darkMode == DarkMode.DARK
            Icon(
                imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                contentDescription = "Alternar modo claro/oscuro"
            )
        }

        // Menú para elegir tema de marca
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Palette, contentDescription = "Elegir tema")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.displayName) },
                    onClick = {
                        controller.selectTheme(theme)
                        expanded = false
                    }
                )
            }
            DarkMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text("Modo: ${mode.displayName}") },
                    onClick = {
                        controller.selectDarkMode(mode)
                        expanded = false
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp
                    )
                )
            }
        }
    }
}
