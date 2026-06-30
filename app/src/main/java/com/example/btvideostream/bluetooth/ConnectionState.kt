package com.example.btvideostream.bluetooth

import android.bluetooth.BluetoothDevice

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val device: BluetoothDevice) : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
