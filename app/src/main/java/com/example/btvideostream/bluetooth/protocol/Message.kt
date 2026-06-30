package com.example.btvideostream.bluetooth.protocol

/**
 * Tipos de mensaje del protocolo cliente-servidor.
 * El encabezado de cada frame es: [tipo:1 byte][longitud:4 bytes big-endian][payload:N bytes]
 */
enum class MessageType(val id: Byte) {
    PING(0x01),
    PONG(0x02),
    SEARCH_REQUEST(0x10),
    SEARCH_RESULTS(0x11),
    VIDEO_REQUEST(0x20),
    VIDEO_CHUNK(0x21),
    VIDEO_END(0x22),
    VIDEO_ERROR(0x23),
    STATUS(0x30),
    DISCONNECT(0xFF.toByte());

    companion object {
        fun fromId(id: Byte): MessageType? = entries.find { it.id == id }
    }
}

sealed class Message {
    abstract val type: MessageType

    data object Ping : Message() { override val type = MessageType.PING }
    data object Pong : Message() { override val type = MessageType.PONG }
    data object Disconnect : Message() { override val type = MessageType.DISCONNECT }

    /** Cliente → Servidor: texto de búsqueda. source: "yt" | "tt" | "web" */
    data class SearchRequest(val query: String, val source: String = "yt") : Message() {
        override val type = MessageType.SEARCH_REQUEST
    }

    /** Servidor → Cliente: lista de resultados JSON compacto */
    data class SearchResults(val json: String) : Message() {
        override val type = MessageType.SEARCH_RESULTS
    }

    /** Cliente → Servidor: ID/URL del video a reproducir + calidad deseada */
    data class VideoRequest(val videoId: String, val quality: VideoQuality) : Message() {
        override val type = MessageType.VIDEO_REQUEST
    }

    /** Servidor → Cliente: fragmento de video (raw bytes) */
    data class VideoChunk(val index: Int, val data: ByteArray) : Message() {
        override val type = MessageType.VIDEO_CHUNK
    }

    /** Servidor → Cliente: fin de stream */
    data object VideoEnd : Message() { override val type = MessageType.VIDEO_END }

    /** Servidor → Cliente: error al obtener el video */
    data class VideoError(val reason: String) : Message() {
        override val type = MessageType.VIDEO_ERROR
    }

    /** Cualquier dirección: estado de la conexión o del buffer */
    data class Status(val payload: String) : Message() {
        override val type = MessageType.STATUS
    }
}

enum class VideoQuality(val label: String) {
    LOW("144p"),
    MEDIUM("360p"),
    HIGH("720p"),
    AUDIO_ONLY("audio")
}
