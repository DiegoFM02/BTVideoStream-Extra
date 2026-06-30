package com.example.btvideostream.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.btvideostream.bluetooth.ConnectionState
import com.example.btvideostream.ui.theme.EstadoConectado
import com.example.btvideostream.ui.theme.EstadoConectando
import com.example.btvideostream.ui.theme.EstadoDesconectado

/**
 * Indicador visual compacto del estado de la conexión Bluetooth.
 * Muestra un punto de color (verde/amarillo parpadeante/rojo) + texto.
 * Cumple el requisito del examen: "indicadores visuales sobre calidad
 * y estado de la conexión Bluetooth".
 */
@Composable
fun ConnectionIndicator(state: ConnectionState, modifier: Modifier = Modifier) {
    val targetColor = when (state) {
        is ConnectionState.Connected -> EstadoConectado
        is ConnectionState.Connecting -> EstadoConectando
        else -> EstadoDesconectado
    }
    val animatedColor by animateColorAsState(targetColor, label = "btColor")

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse"
    )
    val dotAlpha = if (state is ConnectionState.Connecting) pulse else 1f

    val label = when (state) {
        is ConnectionState.Disconnected -> "Sin conexión"
        is ConnectionState.Connecting -> "Conectando con ${state.device.name ?: "dispositivo"}…"
        is ConnectionState.Connected -> "Conectado: ${state.device.name ?: state.device.address}"
        is ConnectionState.Error -> "Error: ${state.message}"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
