package animelovers.rebornhd.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import animelovers.rebornhd.R
import animelovers.rebornhd.data.model.Anime
import animelovers.rebornhd.databinding.ItemBrowseGridBinding
import animelovers.rebornhd.databinding.ItemBrowseListBinding
import animelovers.rebornhd.databinding.ItemInfoBinding
import animelovers.rebornhd.databinding.ItemMostviewBinding
import animelovers.rebornhd.databinding.ItemSlideBinding
import animelovers.rebornhd.databinding.ItemUpdateBinding
import coil.load

/**
 * Adapter kartu poster (Beranda + Jelajahi grid + Lainnya + Favorit).
 * Satu adapter, tiga layout item — polanya sama (poster 100×150dp + judul).
 */

private fun loadPoster(view: android.widget.ImageView, url: String) {
    view.load(url) {
        placeholder(R.drawable.splashh)
        error(R.drawable.splashh)
        crossfade(true)
    }
}

/** Item "Episode Baru" (item_update): poster + judul + status + badge episode. */
class UpdateAdapter(
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<UpdateAdapter.Holder>() {

    private var items: List<Anime> = emptyList()

    fun submitList(list: List<Anime>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemUpdateBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemUpdateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val b = holder.binding
        loadPoster(b.posterView, anime.poster)
        b.titleView.text = anime.displayTitle
        b.statusTextUpdate.text = anime.status
        b.statusTextUpdate.visibility =
            if (anime.status.isBlank()) View.GONE else View.VISIBLE
        val epCount = anime.episodeCount
        b.episodeUpdate.text = if (epCount != null) "Ep $epCount" else ""
        b.episodeUpdate.visibility =
            if (epCount != null) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = items.size
}

/** Item "Terlaris" (item_mostview): poster + judul + badge skor + status.
 *  Anikoto tidak memberi jumlah tonton — badge menampilkan skor sebagai
 *  proksi "paling banyak ditonton" (diurutkan skor tertinggi dulu). */
class MostViewAdapter(
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<MostViewAdapter.Holder>() {

    private var items: List<Anime> = emptyList()

    fun submitList(list: List<Anime>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemMostviewBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMostviewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val b = holder.binding
        loadPoster(b.posterViewMostview, anime.poster)
        b.titleViewMostview.text = anime.displayTitle
        b.textView4maxview.text = if (anime.score.isBlank()) "" else "★ ${anime.score}"
        b.textView4maxview.visibility =
            if (anime.score.isBlank()) View.GONE else View.VISIBLE
        b.statusTextmost.text = anime.status
        b.statusTextmost.visibility =
            if (anime.status.isBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = items.size
}

/** Item grid Jelajahi / Lainnya / Favorit (item_browse_grid). */
class BrowseGridAdapter(
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<BrowseGridAdapter.Holder>() {

    private var items: List<Anime> = emptyList()

    fun submitList(list: List<Anime>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemBrowseGridBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBrowseGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val b = holder.binding
        loadPoster(b.posterViewBrows, anime.poster)
        b.titleViewBrows.text = anime.displayTitle
    }

    override fun getItemCount(): Int = items.size
}

/** Item list Jelajahi (item_browse_list): poster + judul + status + episode + genre. */
class BrowseListAdapter(
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<BrowseListAdapter.Holder>() {

    private var items: List<Anime> = emptyList()

    fun submitList(list: List<Anime>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemBrowseListBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBrowseListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val b = holder.binding
        loadPoster(b.posterViewBrows, anime.poster)
        b.titleViewBrows.text = anime.displayTitle
        b.statusText.text = anime.status
        val epCount = anime.episodeCount
        b.maxepisode.text = if (epCount != null) "Episode: $epCount" else ""
        b.genreTextAll.text = anime.genres.joinToString(", ")
    }

    override fun getItemCount(): Int = items.size
}

/** Item slider Beranda (item_slide): gambar penuh + judul. */
class SlideAdapter(
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<SlideAdapter.Holder>() {

    private var items: List<Anime> = emptyList()

    fun submitList(list: List<Anime>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemSlideBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSlideBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        holder.binding.slideImage.load(anime.poster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
            crossfade(true)
        }
        holder.binding.slideNameTXT.text = anime.displayTitle
    }

    override fun getItemCount(): Int = items.size
}

/** Item pengumuman "Info" (item_info). v1: daftar kosong (tidak ada pengumuman
 *  dari backend) — adapter tetap disediakan untuk Backend nanti. */
class InfoAdapter : RecyclerView.Adapter<InfoAdapter.Holder>() {

    data class InfoItem(val date: String, val title: String)

    private var items: List<InfoItem> = emptyList()

    fun submitList(list: List<InfoItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemInfoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemInfoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.tglInfo.text = item.date
        holder.binding.infoTitle.text = item.title
    }

    override fun getItemCount(): Int = items.size
}
