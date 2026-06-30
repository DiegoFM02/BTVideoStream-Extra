package com.example.btvideostream.bluetooth.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Protocolo de framing binario sobre RFCOMM:
 *   [tipo: 1 byte] [longitud payload: 4 bytes BE] [payload: N bytes]
 *
 * Los campos de texto viajan como UTF-8.
 * VideoChunk lleva: [4 bytes índice BE] + [bytes del chunk].
 * VideoRequest lleva: [1 byte calidad] + [bytes del videoId en UTF-8].
 */
object MessageSerializer {

    fun write(out: OutputStream, message: Message) {
        val dos = DataOutputStream(out)
        val payload = encodePayload(message)
        dos.writeByte(message.type.id.toInt())
        dos.writeInt(payload.size)
        dos.write(payload)
        dos.flush()
    }

    // Límite de seguridad: ningún mensaje legítimo supera 20 MB
    private const val MAX_PAYLOAD = 20 * 1024 * 1024

    fun read(input: InputStream): Message? {
        val dis = DataInputStream(input)
        val typeId = dis.readByte()
        val type = MessageType.fromId(typeId) ?: return null
        val length = dis.readInt()
        if (length < 0 || length > MAX_PAYLOAD) {
            // Desync de protocolo — la conexión necesita reiniciarse
            throw IOException("Protocolo desync: longitud inválida $length (tipo=$typeId)")
        }
        val payload = ByteArray(length)
        dis.readFully(payload)
        return decodePayload(type, payload)
    }

    private fun encodePayload(message: Message): ByteArray = when (message) {
        is Message.Ping, is Message.Pong,
        is Message.VideoEnd, is Message.Disconnect -> ByteArray(0)

        is Message.SearchRequest -> "${message.source}|${message.query}".toByteArray(Charsets.UTF_8)
        is Message.SearchResults -> message.json.toByteArray(Charsets.UTF_8)
        is Message.VideoError -> message.reason.toByteArray(Charsets.UTF_8)
        is Message.Status -> message.payload.toByteArray(Charsets.UTF_8)

        is Message.VideoRequest -> {
            val idBytes = message.videoId.toByteArray(Charsets.UTF_8)
            ByteArray(1 + idBytes.size).also {
                it[0] = message.quality.ordinal.toByte()
                idBytes.copyInto(it, 1)
            }
        }

        is Message.VideoChunk -> {
            val buf = ByteArray(4 + message.data.size)
            buf[0] = (message.index shr 24).toByte()
            buf[1] = (message.index shr 16).toByte()
            buf[2] = (message.index shr 8).toByte()
            buf[3] = message.index.toByte()
            message.data.copyInto(buf, 4)
            buf
        }
    }

    private fun decodePayload(type: MessageType, payload: ByteArray): Message = when (type) {
        MessageType.PING -> Message.Ping
        MessageType.PONG -> Message.Pong
        MessageType.VIDEO_END -> Message.VideoEnd
        MessageType.DISCONNECT -> Message.Disconnect

        MessageType.SEARCH_REQUEST -> {
            val raw = String(payload, Charsets.UTF_8)
            val sep = raw.indexOf('|')
            if (sep >= 0) Message.SearchRequest(raw.substring(sep + 1), raw.substring(0, sep))
            else Message.SearchRequest(raw, "yt")
        }
        MessageType.SEARCH_RESULTS -> Message.SearchResults(String(payload, Charsets.UTF_8))
        MessageType.VIDEO_ERROR -> Message.VideoError(String(payload, Charsets.UTF_8))
        MessageType.STATUS -> Message.Status(String(payload, Charsets.UTF_8))

        MessageType.VIDEO_REQUEST -> {
            val quality = VideoQuality.entries.getOrElse(payload[0].toInt()) { VideoQuality.MEDIUM }
            val videoId = String(payload, 1, payload.size - 1, Charsets.UTF_8)
            Message.VideoRequest(videoId, quality)
        }

        MessageType.VIDEO_CHUNK -> {
            val index = ((payload[0].toInt() and 0xFF) shl 24) or
                        ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)
            val data = payload.copyOfRange(4, payload.size)
            Message.VideoChunk(index, data)
        }
    }
}
