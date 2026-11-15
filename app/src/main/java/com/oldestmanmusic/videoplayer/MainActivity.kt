package com.oldestmanmusic.videoplayer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.oldestmanmusic.videoplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializePlayer()

        binding.playButton.setOnClickListener {
            val url = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                Toast.makeText(this, "Ingresa una URL válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mediaItem = MediaItem.fromUri(url)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.playWhenReady = true
        }

        binding.pauseButton.setOnClickListener {
            player?.pause()
        }

        binding.stopButton.setOnClickListener {
            player?.stop()
            player?.clearMediaItems()
        }

        binding.urlInput.setText("https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4")
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player
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
