package com.example.btvideostream.ui.client

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.btvideostream.bluetooth.BluetoothManager
import com.example.btvideostream.bluetooth.ConnectionState
import com.example.btvideostream.bluetooth.protocol.Message
import com.example.btvideostream.bluetooth.protocol.VideoQuality
import com.example.btvideostream.notifications.BtNotificationManager
import com.example.btvideostream.server.VideoResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import java.io.File

class ClientViewModel(app: Application) : AndroidViewModel(app) {

    val btManager = BluetoothManager(app)
    private val notif = BtNotificationManager(app)
    private var lastConnectedDevice: BluetoothDevice? = null

    val connectionState: StateFlow<ConnectionState> = btManager.connectionState

    private val _searchResults = MutableStateFlow<List<VideoResult>>(emptyList())
    val searchResults: StateFlow<List<VideoResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isReceivingVideo = MutableStateFlow(false)
    val isReceivingVideo: StateFlow<Boolean> = _isReceivingVideo.asStateFlow()

    private val _videoFile = MutableStateFlow<File?>(null)
    val videoFile: StateFlow<File?> = _videoFile.asStateFlow()

    private val _errorMsg = MutableStateFlow<String?>(null)
    val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()

    val pairedDevices: List<BluetoothDevice> = btManager.pairedDevices()

    // Buffer de chunks recibidos
    private val chunkBuffer = mutableMapOf<Int, ByteArray>()
    private var totalChunksReceived = 0
    private val videoOutFile = File(app.cacheDir, "received_video.mp4")

    init {
        btManager.connectionState.onEach { state ->
            when (state) {
                is ConnectionState.Connected -> {
                    notif.notifyConnected(state.device.name ?: state.device.address)
                    lastConnectedDevice = state.device
                }
                is ConnectionState.Disconnected -> {
                    notif.notifyDisconnected()
                    _isReceivingVideo.value = false
                    _isSearching.value = false
                    // No auto-reconectar: evita el loop de desync
                    // El usuario reconecta desde la lista de dispositivos
                }
                else -> {}
            }
        }.launchIn(viewModelScope)

        btManager.incomingMessages.onEach { msg ->
            when (msg) {
                is Message.SearchResults -> {
                    _isSearching.value = false
                    _searchResults.value = parseResults(msg.json)
                }
                is Message.VideoChunk -> {
                    _isReceivingVideo.value = true
                    chunkBuffer[msg.index] = msg.data
                    totalChunksReceived++
                }
                is Message.VideoEnd -> {
                    assembleVideo()
                    _isReceivingVideo.value = false
                }
                is Message.VideoError -> {
                    _isReceivingVideo.value = false
                    _isSearching.value = false
                    _errorMsg.value = msg.reason
                }
                else -> {}
            }
        }.launchIn(viewModelScope)
    }

    fun connectTo(device: BluetoothDevice) = btManager.connectToDevice(device)

    fun search(query: String, source: String = "yt") {
        _searchResults.value = emptyList()
        _isSearching.value = true
        btManager.send(Message.SearchRequest(query, source))
    }

    fun requestVideo(videoId: String, quality: VideoQuality = VideoQuality.LOW) {
        chunkBuffer.clear()
        totalChunksReceived = 0
        _videoFile.value = null
        _isReceivingVideo.value = true
        btManager.send(Message.VideoRequest(videoId, quality))
    }

    fun clearError() { _errorMsg.value = null }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    /** Pide al servidor que abra la URL en su propio navegador. */
    fun requestOpenUrl(url: String) = btManager.send(Message.Status("OPEN_URL:$url"))

    private fun assembleVideo() {
        if (chunkBuffer.isEmpty()) return
        videoOutFile.outputStream().use { out ->
            val sorted = chunkBuffer.entries.sortedBy { it.key }
            sorted.forEach { (_, bytes) -> out.write(bytes) }
        }
        _videoFile.value = videoOutFile
        chunkBuffer.clear()
    }

    private fun parseResults(json: String): List<VideoResult> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            VideoResult(
                videoId = obj.getString("id"),
                title = obj.optString("title", ""),
                channel = obj.optString("channel", ""),
                duration = obj.optString("duration", ""),
                // Para resultados web, guardamos la URL real en thumbnailUrl
                thumbnailUrl = obj.optString("url", obj.optString("thumb", "")),
            )
        }
    }

    override fun onCleared() {
        btManager.disconnect()
        super.onCleared()
    }
}
