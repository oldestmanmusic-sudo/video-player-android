package com.oldestmanmusic.videoplayer

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.oldestmanmusic.videoplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var currentUrl: String = ""
    private val playlist = mutableListOf<VideoItem>()
    private val favorites = mutableSetOf<String>()
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("video_player_prefs", MODE_PRIVATE)
        favorites.addAll(readFavorites())

        initializePlayer()
        initializeRecyclerView()

        binding.playButton.setOnClickListener {
            playCurrentUrl()
        }

        binding.loadPlaylistButton.setOnClickListener {
            val input = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (input.isEmpty()) {
                Toast.makeText(this, "Ingresa una URL de video o playlist", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadPlaylist(input)
        }

        binding.favoriteButton.setOnClickListener {
            val url = currentUrl
            if (url.isEmpty()) {
                Toast.makeText(this, "Primero selecciona o reproduce un video", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (favorites.contains(url)) {
                favorites.remove(url)
                Toast.makeText(this, "Eliminado de favoritos", Toast.LENGTH_SHORT).show()
            } else {
                favorites.add(url)
                Toast.makeText(this, "Añadido a favoritos", Toast.LENGTH_SHORT).show()
            }
            saveFavorites(favorites)
            adapter.updateData(getCurrentDisplayList())
            updateFavoriteButtonState()
        }

        binding.showPlaylistButton.setOnClickListener {
            adapter.updateData(playlist)
            binding.sectionTitle.text = "Lista de reproducción"
        }

        binding.showFavoritesButton.setOnClickListener {
            adapter.updateData(favorites.map { VideoItem(title = it.substringAfterLast('/'), url = it, isLive = it.contains("m3u8", ignoreCase = true)) })
            binding.sectionTitle.text = "Favoritos"
        }

        val defaultUrl = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
        binding.urlInput.setText(defaultUrl)
        playVideo(VideoItem("Big Buck Bunny", defaultUrl, false))
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player
    }

    private fun initializeRecyclerView() {
        adapter = VideoAdapter(
            items = mutableListOf(),
            onPlay = ::playVideo,
            onToggleFavorite = ::toggleFavorite,
            favoriteUrls = favorites
        )
        binding.videoList.layoutManager = LinearLayoutManager(this)
        binding.videoList.adapter = adapter
        binding.sectionTitle.text = "Lista de reproducción"
    }

    private fun playCurrentUrl() {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            Toast.makeText(this, "Ingresa una URL válida", Toast.LENGTH_SHORT).show()
            return
        }
        playVideo(VideoItem(title = url.substringAfterLast('/').substringBefore('?').ifBlank { "Video" }, url = url))
    }

    private fun playVideo(video: VideoItem) {
        if (video.url.isBlank()) return

        currentUrl = video.url
        updateFavoriteButtonState()
        binding.urlInput.setText(video.url)

        val mediaItem = MediaItem.fromUri(video.url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true

        Toast.makeText(this, "Reproduciendo: ${video.title}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFavorite(video: VideoItem) {
        if (favorites.contains(video.url)) {
            favorites.remove(video.url)
        } else {
            favorites.add(video.url)
        }
        saveFavorites(favorites)
        adapter.updateData(getCurrentDisplayList())
        updateFavoriteButtonState()
    }

    private fun updateFavoriteButtonState() {
        binding.favoriteButton.text = if (favorites.contains(currentUrl)) "Eliminar de favoritos" else "Añadir a favoritos"
    }

    private fun loadPlaylist(input: String) {
        val url = input.trim()

        try {
            if (url.contains(".m3u", ignoreCase = true) || url.contains("playlist", ignoreCase = true)) {
                val parsed = PlaylistParser.fetchPlaylist(url)
                if (parsed.isEmpty()) {
                    Toast.makeText(this, "No se pudieron leer elementos del playlist", Toast.LENGTH_SHORT).show()
                    return
                }
                playlist.clear()
                playlist.addAll(parsed)
                adapter.updateData(parsed)
                binding.sectionTitle.text = "Lista de reproducción"
                Toast.makeText(this, "Playlist cargada: ${parsed.size} elementos", Toast.LENGTH_SHORT).show()
                return
            }

            val singleVideo = VideoItem(
                title = url.substringAfterLast('/').substringBefore('?').ifBlank { "Video" },
                url = url,
                isLive = false
            )
            playlist.clear()
            playlist.add(singleVideo)
            adapter.updateData(playlist)
            binding.sectionTitle.text = "Lista de reproducción"
            playVideo(singleVideo)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error cargando playlist", e)
            Toast.makeText(this, "No se pudo cargar la URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentDisplayList(): List<VideoItem> {
        return if (binding.sectionTitle.text == "Favoritos") {
            favorites.map { VideoItem(title = it.substringAfterLast('/'), url = it, isLive = it.contains("m3u8", ignoreCase = true)) }
        } else {
            playlist
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
