package com.example.btvideostream.server

data class VideoResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumbnailUrl: String
)

data class StreamInfo(
    val url: String,
    val quality: String,
    val mimeType: String,
    val title: String,
    val durationSeconds: Long,
    val cookies: String? = null
)
