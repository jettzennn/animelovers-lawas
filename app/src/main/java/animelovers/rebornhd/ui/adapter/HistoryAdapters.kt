package animelovers.rebornhd.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import animelovers.rebornhd.R
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.rewritePosterHost
import animelovers.rebornhd.databinding.ItemEpisodeBinding
import animelovers.rebornhd.databinding.ItemFavoritesHeaderBinding
import animelovers.rebornhd.databinding.ItemHistoryBinding
import animelovers.rebornhd.databinding.ItemHistoryDetailBinding
import animelovers.rebornhd.util.formatWaktu
import coil.load

/** Sel grid episode (item_episode): chip putih "Episode {n}". */
class EpisodeAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {

    private var episodes: List<Int> = emptyList()

    fun submitList(numbers: List<Int>) {
        episodes = numbers
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(episodes[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEpisodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.idtitlePlay.text =
            holder.itemView.context.getString(R.string.episode_label, episodes[position])
    }

    override fun getItemCount(): Int = episodes.size
}

/** Daftar riwayat (item_history): poster + judul + episode + waktu + hapus. */
class HistoryAdapter(
    private val onClick: (VideoEntity) -> Unit,
    private val onDelete: (VideoEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private var items: List<VideoEntity> = emptyList()

    fun submitList(list: List<VideoEntity>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            binding.imageViewTrash.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.posterhistoryView.load(rewritePosterHost(item.idposter)) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
            crossfade(true)
        }
        b.titlehistoryView.text = item.title
        b.episodehistoryView.text =
            b.root.context.getString(R.string.episode_label, item.idepisode.toIntOrNull() ?: 0)
        b.timehistoryView.text = formatWaktu(item.waktu)
    }

    override fun getItemCount(): Int = items.size
}

/** Kartu "Lanjutkan" di Detail (item_history_detail): satu baris riwayat anime ini. */
class HistoryDetailAdapter(
    private val onClick: (VideoEntity) -> Unit
) : RecyclerView.Adapter<HistoryDetailAdapter.Holder>() {

    private var items: List<VideoEntity> = emptyList()

    fun submitList(list: List<VideoEntity>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemHistoryDetailBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemHistoryDetailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.posterhistorydetView.load(rewritePosterHost(item.idposter)) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
            crossfade(true)
        }
        b.episodehistorydetView.text = item.idepisode
        b.timehistorydetView.text = formatWaktu(item.waktu)
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Header seksi Favorit di tab Riwayat (item_favorites_header).
 * Dipakai sebagai adapter pertama ConcatAdapter — itemCount 0 bila belum
 * ada favorit, sehingga seksi hilang seluruhnya (desain.md §4.3).
 */
class FavoritesHeaderAdapter(
    private val onClick: (SankaAnime) -> Unit
) : RecyclerView.Adapter<FavoritesHeaderAdapter.Holder>() {

    private var items: List<FavoriteEntity> = emptyList()
    private var gridAdapter: BrowseGridAdapter? = null

    fun submitList(list: List<FavoriteEntity>) {
        val hadItems = items.isNotEmpty()
        items = list
        if (hadItems != list.isNotEmpty()) {
            notifyDataSetChanged()
        } else if (list.isNotEmpty()) {
            notifyItemChanged(0)
        }
    }

    inner class Holder(val binding: ItemFavoritesHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.recyclerViewFavorite.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            gridAdapter = BrowseGridAdapter(onClick)
            binding.recyclerViewFavorite.adapter = gridAdapter
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemFavoritesHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        gridAdapter?.submitList(items.map {
            SankaAnime(slug = it.idtitle, title = it.title, poster = it.idposter)
        })
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else 1
}
