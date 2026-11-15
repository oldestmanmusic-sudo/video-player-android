package com.oldestmanmusic.videoplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.oldestmanmusic.videoplayer.databinding.ItemVideoBinding

class VideoAdapter(
    private val items: MutableList<VideoItem>,
    private val onPlay: (VideoItem) -> Unit,
    private val onToggleFavorite: (VideoItem) -> Unit,
    private val favoriteUrls: Set<String>
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, favoriteUrls.contains(item.url), onPlay, onToggleFavorite)
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: VideoItem,
            isFavorite: Boolean,
            onPlay: (VideoItem) -> Unit,
            onToggleFavorite: (VideoItem) -> Unit
        ) {
            binding.videoTitle.text = item.title
            binding.videoUrl.text = item.url
            binding.videoBadge.text = if (item.isLive) "LIVE" else "VIDEO"
            binding.favoriteButton.text = if (isFavorite) "★" else "☆"

            binding.root.setOnClickListener { onPlay(item) }
            binding.favoriteButton.setOnClickListener { onToggleFavorite(item) }
        }
    }
}
