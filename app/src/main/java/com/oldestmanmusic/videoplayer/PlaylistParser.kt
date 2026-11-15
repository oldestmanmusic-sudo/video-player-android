package com.oldestmanmusic.videoplayer

import android.content.Context
import android.util.Log
import java.net.URL

object PlaylistParser {
    private const val TAG = "PlaylistParser"

    fun parsePlaylist(content: String): List<VideoItem> {
        val parsed = mutableListOf<VideoItem>()
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        var currentTitle: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    currentTitle = line.substringAfter(',').ifBlank { "Video sin título" }
                }
                line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXTGRP") -> {
                    continue
                }
                line.startsWith("#") -> {
                    continue
                }
                line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp") || line.startsWith("/") || line.startsWith("file:") -> {
                    val title = currentTitle ?: extractTitleFromUrl(line)
                    parsed.add(
                        VideoItem(
                            title = title,
                            url = line,
                            isLive = line.contains("m3u8", ignoreCase = true) || line.contains("playlist", ignoreCase = true)
                        )
                    )
                    currentTitle = null
                }
            }
        }

        return parsed.distinctBy { it.url }
    }

    fun fetchPlaylist(urlString: String): List<VideoItem> {
        val content = try {
            URL(urlString).openStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching playlist: ${e.message}", e)
            return emptyList()
        }

        return parsePlaylist(content)
    }

    fun resolvePlaylistUrl(baseUrl: String, item: VideoItem): String {
        return when {
            item.url.startsWith("http") -> item.url
            item.url.startsWith("/") -> "$baseUrl${item.url}"
            else -> {
                val normalizedBase = if (baseUrl.endsWith('/')) baseUrl.dropLast(1) else baseUrl
                "$normalizedBase/${item.url}"
            }
        }
    }

    private fun extractTitleFromUrl(value: String): String {
        return value.substringAfterLast('/').substringBefore('?').ifBlank { "Video" }
    }
}

fun Context.readFavorites(): Set<String> {
    return getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet()) ?: emptySet()
}

fun Context.saveFavorites(favorites: Set<String>) {
    getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("favorites", favorites)
        .apply()
}
