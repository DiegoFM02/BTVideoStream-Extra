package com.example.btvideostream.ui.server

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.btvideostream.bluetooth.BluetoothManager
import com.example.btvideostream.bluetooth.ConnectionState
import com.example.btvideostream.bluetooth.protocol.Message
import com.example.btvideostream.notifications.BtNotificationManager
import com.example.btvideostream.server.VideoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ServerViewModel(app: Application) : AndroidViewModel(app) {

    val btManager = BluetoothManager(app)
    private val notif = BtNotificationManager(app)
    private val repo = VideoRepository(app, btManager)

    val connectionState: StateFlow<ConnectionState> = btManager.connectionState

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // Job del proceso de video actual — se cancela si cae la conexión
    private var videoJob: Job? = null
    // Job de keepalive — manda STATUS cada 3s para que BT no cierre por inactividad
    private var keepaliveJob: Job? = null

    init {
        btManager.connectionState.onEach { state ->
            when (state) {
                is ConnectionState.Connected -> {
                    notif.notifyConnected(state.device.name ?: state.device.address)
                    addLog("Cliente conectado: ${state.device.name ?: state.device.address}")
                    startKeepalive()
                }
                is ConnectionState.Disconnected -> {
                    notif.notifyDisconnected()
                    cancelVideoJob()
                    addLog("Cliente desconectado — volviendo a escuchar…")
                    startListening()
                }
                is ConnectionState.Error -> {
                    cancelVideoJob()
                    addLog("Error BT: ${state.message} — reiniciando…")
                    startListening()
                }
                else -> {}
            }
        }.launchIn(viewModelScope)

        btManager.incomingMessages.onEach { msg ->
            when (msg) {
                is Message.Ping -> {
                    addLog("PING → PONG")
                    btManager.send(Message.Pong)
                }
                is Message.SearchRequest -> {
                    addLog("Búsqueda [${msg.source}]: \"${msg.query}\"")
                    viewModelScope.launch { repo.handleSearch(msg.query, msg.source) }
                }
                is Message.VideoRequest -> {
                    addLog("Video: ${msg.videoId} [${msg.quality.label}]")
                    cancelVideoJob()
                    videoJob = viewModelScope.launch {
                        repo.handleVideoRequest(msg.videoId, msg.quality)
                        videoJob = null
                    }
                }
                is Message.Disconnect -> {
                    addLog("Cliente cerró la conexión")
                    cancelVideoJob()
                    btManager.disconnect()
                    startListening()
                }
                is Message.Status -> {
                    if (msg.payload.startsWith("OPEN_URL:")) {
                        val url = msg.payload.removePrefix("OPEN_URL:")
                        addLog("Abriendo en navegador: $url")
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            getApplication<Application>().startActivity(intent)
                        }
                    }
                }
                else -> {}
            }
        }.launchIn(viewModelScope)

        startListening()
    }

    fun startListening() {
        addLog("Esperando conexión Bluetooth…")
        btManager.startServer()
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                // Keepalive siempre activo — previene que BT cierre por inactividad
                btManager.send(Message.Status("ok"))
            }
        }
    }

    private fun cancelVideoJob() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        videoJob?.cancel()
        videoJob = null
    }

    private fun addLog(entry: String) {
        _log.value = (_log.value + entry).takeLast(50)
    }

    override fun onCleared() {
        cancelVideoJob()
        btManager.disconnect()
        super.onCleared()
    }
}
