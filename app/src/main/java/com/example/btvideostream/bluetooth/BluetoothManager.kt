package com.example.btvideostream.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBtManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.btvideostream.bluetooth.protocol.Message
import com.example.btvideostream.bluetooth.protocol.MessageSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Gestiona la conexión Bluetooth RFCOMM (Classic / SPP) para ambos roles.
 *
 * Escrituras al socket: todas van a un Channel (cola FIFO).
 * Un único writer coroutine drena la cola — sin race conditions.
 */
@SuppressLint("MissingPermission")
class BluetoothManager(private val context: Context) {

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "BTVideoStream"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val CHUNK_SIZE = 2048
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBtManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var serverJob: Job? = null

    // Cola de mensajes salientes — un solo writer la drena, sin race conditions
    private val writeChannel = Channel<Message>(capacity = Channel.UNLIMITED)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages

    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    // ─────────────────────────── SERVIDOR ───────────────────────────

    fun startServer() {
        serverJob?.cancel()
        readJob?.cancel()
        writeJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        socket = null; serverSocket = null

        serverJob = scope.launch {
            delay(300)
            _connectionState.value = ConnectionState.Disconnected
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                val client = serverSocket?.accept() ?: return@launch
                serverSocket?.close(); serverSocket = null
                socket = client
                _connectionState.value = ConnectionState.Connected(client.remoteDevice)
                startWriter()
                startReading()
            } catch (e: IOException) {
                if (isActive) _connectionState.value = ConnectionState.Error("Servidor: ${e.message}")
            }
        }
    }

    // ─────────────────────────── CLIENTE ────────────────────────────

    fun connectToDevice(device: BluetoothDevice) {
        scope.launch {
            var attempts = 0
            while (attempts < MAX_RECONNECT_ATTEMPTS) {
                _connectionState.value = ConnectionState.Connecting(device)
                try {
                    val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    adapter?.cancelDiscovery()
                    s.connect()
                    socket = s
                    _connectionState.value = ConnectionState.Connected(device)
                    startWriter()
                    startReading()
                    return@launch
                } catch (e: IOException) {
                    attempts++
                    if (attempts < MAX_RECONNECT_ATTEMPTS) delay(RECONNECT_DELAY_MS)
                    else _connectionState.value =
                        ConnectionState.Error("No se pudo conectar tras $attempts intentos")
                }
            }
        }
    }

    // ──────────────────────── WRITER (cola FIFO) ──────────────────────

    /**
     * Único coroutine que escribe al socket. Drena el Channel en orden.
     * Garantiza que keepalive y chunks nunca se mezclen.
     */
    private fun startWriter() {
        writeJob?.cancel()
        writeJob = scope.launch {
            for (message in writeChannel) {
                val out = socket?.outputStream ?: break
                try {
                    MessageSerializer.write(out, message)
                } catch (e: IOException) {
                    _connectionState.value = ConnectionState.Disconnected
                    break
                }
            }
        }
    }

    // ──────────────────────── LECTURA CONTINUA ──────────────────────

    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            val input = socket?.inputStream ?: return@launch
            while (isActive) {
                try {
                    val msg = MessageSerializer.read(input) ?: continue
                    _incomingMessages.emit(msg)
                } catch (e: IOException) {
                    if (isActive) {
                        try { socket?.close() } catch (_: IOException) {}
                        socket = null
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    break
                }
            }
        }
    }

    // ─────────────────────────── ENVÍO ──────────────────────────────

    /** Encola un mensaje para ser enviado por el writer. No bloquea. */
    fun send(message: Message) {
        writeChannel.trySend(message)
    }

    /** Envía un ByteArray en chunks de [CHUNK_SIZE] bytes seguidos de VideoEnd. */
    fun sendVideoData(data: ByteArray) {
        scope.launch {
            var index = 0
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK_SIZE, data.size)
                writeChannel.trySend(Message.VideoChunk(index++, data.copyOfRange(offset, end)))
                offset = end
            }
            writeChannel.trySend(Message.VideoEnd)
        }
    }

    // ──────────────────────────── CLEANUP ───────────────────────────

    fun disconnect() {
        writeChannel.trySend(Message.Disconnect)
        readJob?.cancel(); writeJob?.cancel(); serverJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        socket = null; serverSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
