package com.oldestmanmusic.videoplayer

data class VideoItem(
    val title: String,
    val url: String,
    val isLive: Boolean = false
)
