package com.oldestmanmusic.videoplayer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
                line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXTGRP") -> continue
                line.startsWith("#") -> continue
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
        .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
}

fun Context.saveFavorites(favorites: Set<String>) {
    getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("favorites", favorites.toSet())
        .apply()
}

fun Context.readPlaylist(): List<VideoItem> {
    val json = getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        .getString("playlist", null) ?: return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val url = entry.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VideoItem(
                title = entry.optString("title").ifBlank { "Video" },
                url = url,
                isLive = entry.optBoolean("isLive", false)
            )
        }
    } catch (e: Exception) {
        Log.e("PlaylistParser", "No se pudo leer la playlist guardada", e)
        emptyList()
    }
}

fun Context.savePlaylist(items: List<VideoItem>) {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("title", item.title)
                .put("url", item.url)
                .put("isLive", item.isLive)
        )
    }
    getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("playlist", array.toString())
        .apply()
}
