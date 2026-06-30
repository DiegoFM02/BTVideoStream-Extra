package com.example.btvideostream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Temas disponibles. El examen pide DOS temas personalizables:
 * Guinda (IPN) y Azul (ESCOM), cada uno con variante clara y oscura.
 * No se usa color dinámico (Material You) para que los colores de marca
 * siempre se respeten en cualquier dispositivo.
 */
enum class AppTheme(val displayName: String) {
    GUINDA("Guinda (IPN)"),
    AZUL("Azul (ESCOM)")
}

/** Modo de oscuridad: sigue al sistema o se fuerza manualmente. */
enum class DarkMode(val displayName: String) {
    SYSTEM("Según sistema"),
    LIGHT("Claro"),
    DARK("Oscuro")
}

private val GuindaLightScheme = lightColorScheme(
    primary = GuindaLightPrimary,
    onPrimary = GuindaLightOnPrimary,
    primaryContainer = GuindaLightPrimaryContainer,
    onPrimaryContainer = GuindaLightOnPrimaryContainer,
    secondary = GuindaLightSecondary,
    onSecondary = GuindaLightOnSecondary,
    secondaryContainer = GuindaLightSecondaryContainer,
    tertiary = GuindaLightTertiary,
    background = GuindaLightBackground,
    onBackground = GuindaLightOnBackground,
    surface = GuindaLightSurface,
    surfaceVariant = GuindaLightSurfaceVariant,
    onSurface = GuindaLightOnSurface,
)

private val GuindaDarkScheme = darkColorScheme(
    primary = GuindaDarkPrimary,
    onPrimary = GuindaDarkOnPrimary,
    primaryContainer = GuindaDarkPrimaryContainer,
    onPrimaryContainer = GuindaDarkOnPrimaryContainer,
    secondary = GuindaDarkSecondary,
    onSecondary = GuindaDarkOnSecondary,
    secondaryContainer = GuindaDarkSecondaryContainer,
    tertiary = GuindaDarkTertiary,
    background = GuindaDarkBackground,
    onBackground = GuindaDarkOnBackground,
    surface = GuindaDarkSurface,
    surfaceVariant = GuindaDarkSurfaceVariant,
    onSurface = GuindaDarkOnSurface,
)

private val AzulLightScheme = lightColorScheme(
    primary = AzulLightPrimary,
    onPrimary = AzulLightOnPrimary,
    primaryContainer = AzulLightPrimaryContainer,
    onPrimaryContainer = AzulLightOnPrimaryContainer,
    secondary = AzulLightSecondary,
    onSecondary = AzulLightOnSecondary,
    secondaryContainer = AzulLightSecondaryContainer,
    tertiary = AzulLightTertiary,
    background = AzulLightBackground,
    onBackground = AzulLightOnBackground,
    surface = AzulLightSurface,
    surfaceVariant = AzulLightSurfaceVariant,
    onSurface = AzulLightOnSurface,
)

private val AzulDarkScheme = darkColorScheme(
    primary = AzulDarkPrimary,
    onPrimary = AzulDarkOnPrimary,
    primaryContainer = AzulDarkPrimaryContainer,
    onPrimaryContainer = AzulDarkOnPrimaryContainer,
    secondary = AzulDarkSecondary,
    onSecondary = AzulDarkOnSecondary,
    secondaryContainer = AzulDarkSecondaryContainer,
    tertiary = AzulDarkTertiary,
    background = AzulDarkBackground,
    onBackground = AzulDarkOnBackground,
    surface = AzulDarkSurface,
    surfaceVariant = AzulDarkSurfaceVariant,
    onSurface = AzulDarkOnSurface,
)

@Composable
fun BTVideoStreamTheme(
    appTheme: AppTheme = AppTheme.GUINDA,
    darkMode: DarkMode = DarkMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val colorScheme = when (appTheme) {
        AppTheme.GUINDA -> if (dark) GuindaDarkScheme else GuindaLightScheme
        AppTheme.AZUL -> if (dark) AzulDarkScheme else AzulLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
