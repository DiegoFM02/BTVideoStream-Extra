package com.example.btvideostream.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.btvideostream.R

/**
 * Notificaciones push del estado de la conexión Bluetooth.
 * Exigido por el examen: "Notificaciones push sobre cambios en el estado
 * de la conexión (al establecerse o al perderse)".
 */
class BtNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "bt_status"
        private const val NOTIF_ID = 1001
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Estado Bluetooth",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Cambios en la conexión Bluetooth" }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun notifyConnected(deviceName: String) = notify(
        title = "Bluetooth conectado",
        text = "Enlace establecido con $deviceName"
    )

    fun notifyDisconnected() = notify(
        title = "Bluetooth desconectado",
        text = "Se perdió la conexión con el otro dispositivo"
    )

    private fun notify(title: String, text: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // El permiso POST_NOTIFICATIONS puede no estar concedido en API 33+
        }
    }
}
