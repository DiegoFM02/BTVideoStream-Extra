package com.example.btvideostream.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mantiene la preferencia de tema (Guinda/Azul) y de modo oscuro,
 * persistiéndola en SharedPreferences para que sobreviva reinicios.
 * Se expone como estado observable de Compose.
 */
class ThemeController(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    var appTheme by mutableStateOf(
        runCatching { AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.GUINDA.name)!!) }
            .getOrDefault(AppTheme.GUINDA)
    )
        private set

    var darkMode by mutableStateOf(
        runCatching { DarkMode.valueOf(prefs.getString(KEY_DARK, DarkMode.SYSTEM.name)!!) }
            .getOrDefault(DarkMode.SYSTEM)
    )
        private set

    fun selectTheme(theme: AppTheme) {
        appTheme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun selectDarkMode(mode: DarkMode) {
        darkMode = mode
        prefs.edit().putString(KEY_DARK, mode.name).apply()
    }

    /** Alterna rápidamente entre claro y oscuro (botón manual del examen). */
    fun toggleDark() {
        val next = when (darkMode) {
            DarkMode.DARK -> DarkMode.LIGHT
            else -> DarkMode.DARK
        }
        selectDarkMode(next)
    }

    private companion object {
        const val KEY_THEME = "app_theme"
        const val KEY_DARK = "dark_mode"
    }
}
