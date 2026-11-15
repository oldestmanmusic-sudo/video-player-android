package com.oldestmanmusic.videoplayer

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3 library exposed to Android Auto. It deliberately publishes audio-style
 * controls and does not attach a video surface, so Android Auto never displays video.
 */
class CarMediaLibraryService : MediaLibraryService() {
    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
        }
        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(folder(ROOT_ID, getString(R.string.app_name)), params)
                )
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<List<MediaItem>>> {
                val children = when (parentId) {
                    ROOT_ID -> listOf(
                        folder(PLAYLIST_ID, "Lista de reproducción"),
                        folder(FAVORITES_ID, "Favoritos")
                    )
                    PLAYLIST_ID -> readPlaylist().map(::playableItem)
                    FAVORITES_ID -> readFavorites().map { url ->
                        val saved = readPlaylist().firstOrNull { it.url == url }
                        playableItem(saved ?: VideoItem(titleFromUrl(url), url, isLive = isStream(url)))
                    }
                    else -> emptyList()
                }
                val start = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(0)).coerceAtMost(children.size)
                val end = (start + pageSize.coerceAtLeast(0)).coerceAtMost(children.size)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(children.subList(start, end), params)
                )
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val item = when (mediaId) {
                    ROOT_ID -> folder(ROOT_ID, getString(R.string.app_name))
                    PLAYLIST_ID -> folder(PLAYLIST_ID, "Lista de reproducción")
                    FAVORITES_ID -> folder(FAVORITES_ID, "Favoritos")
                    else -> mediaIdToUrl(mediaId)?.let { url ->
                        val saved = readPlaylist().firstOrNull { it.url == url }
                        playableItem(saved ?: VideoItem(titleFromUrl(url), url, isLive = isStream(url)))
                    }
                }
                return Futures.immediateFuture(
                    if (item == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    else LibraryResult.ofItem(item, null)
                )
            }
        }
        librarySession = MediaLibrarySession.Builder(this, player, callback).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession

    override fun onDestroy() {
        librarySession?.run {
            player.release()
            release()
        }
        librarySession = null
        super.onDestroy()
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun playableItem(video: VideoItem): MediaItem = MediaItem.Builder()
        .setMediaId(urlToMediaId(video.url))
        .setUri(video.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(video.title)
                .setArtist(if (video.isLive) "Transmisión en directo" else "Video Player Android")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private fun urlToMediaId(url: String): String = ITEM_PREFIX + Uri.encode(url)

    private fun mediaIdToUrl(mediaId: String): String? =
        if (mediaId.startsWith(ITEM_PREFIX)) Uri.decode(mediaId.removePrefix(ITEM_PREFIX)) else null

    private fun titleFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "Reproducción" }

    private fun isStream(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains("live", ignoreCase = true)

    private companion object {
        const val ROOT_ID = "root"
        const val PLAYLIST_ID = "playlist"
        const val FAVORITES_ID = "favorites"
        const val ITEM_PREFIX = "item:"
    }
}
