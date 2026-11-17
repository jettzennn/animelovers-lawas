package animelovers.rebornhd.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import animelovers.rebornhd.R
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaEpisodeItem
import animelovers.rebornhd.databinding.ItemBrowseGridBinding
import animelovers.rebornhd.databinding.ItemBrowseListBinding
import animelovers.rebornhd.databinding.ItemMostviewBinding
import animelovers.rebornhd.databinding.ItemSlideBinding
import animelovers.rebornhd.databinding.ItemUpdateBinding
import coil.load
import java.text.NumberFormat
import java.util.Locale

/**
 * Adapter kartu poster (Beranda + Jelajahi grid + Lainnya + Favorit).
 * Satu adapter, tiga layout item — polanya sama (poster 100×150dp + judul).
 * v2: model Sanka (SankaAnime / SankaEpisodeItem). Adapter Info DIHAPUS —
 * seksi Info dihapus dari Beranda (desain.md §14).
 */

private fun loadPoster(view: android.widget.ImageView, url: String) {
    view.load(url) {
        placeholder(R.drawable.splashh)
        error(R.drawable.splashh)
        crossfade(true)
    }
}

/** Format jumlah tonton ala Indonesia: 103410 → "103.410". */
private fun formatViews(views: Int): String =
    NumberFormat.getNumberInstance(Locale("id", "ID")).format(views)

/** Item "Episode Baru" (item_update): poster + judul episode + status + badge episode. */
class UpdateAdapter(
    private val onClick: (SankaEpisodeItem) -> Unit
) : RecyclerView.Adapter<UpdateAdapter.Holder>() {

    private var items: List<SankaEpisodeItem> = emptyList()

    fun submitList(list: List<SankaEpisodeItem>) {
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
        val item = items[position]
        val b = holder.binding
        loadPoster(b.posterView, item.posterUrl)
        b.titleView.text = item.title ?: item.animeTitle ?: ""
        b.statusTextUpdate.text = item.animeStatus ?: ""
        b.statusTextUpdate.visibility =
            if (item.animeStatus.isNullOrBlank()) View.GONE else View.VISIBLE
        val ep = item.episodeNumber
        b.episodeUpdate.text = if (ep > 0) "Ep $ep" else ""
        b.episodeUpdate.visibility =
            if (ep > 0) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Item "Terlaris" (item_mostview): poster + judul + badge jumlah tonton + status.
 * Badge HANYA bila Sanka menyediakan jumlah tonton (views > 0) — bila 0,
 * badge disembunyikan (JANGAN angka palsu; desain.md §14.4).
 * Urutan: Sanka `popularWeekly` (sudah terurut oleh server).
 */
class MostViewAdapter(
    private val onClick: (SankaAnime) -> Unit
) : RecyclerView.Adapter<MostViewAdapter.Holder>() {

    private var items: List<SankaAnime> = emptyList()

    fun submitList(list: List<SankaAnime>) {
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
        loadPoster(b.posterViewMostview, anime.posterUrl)
        b.titleViewMostview.text = anime.displayTitle
        b.textView4maxview.text =
            if (anime.views > 0) "👁 ${formatViews(anime.views)}" else ""
        b.textView4maxview.visibility =
            if (anime.views > 0) View.VISIBLE else View.GONE
        b.statusTextmost.text = anime.status ?: ""
        b.statusTextmost.visibility =
            if (anime.status.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = items.size
}

/** Item grid Jelajahi / Lainnya / Favorit (item_browse_grid). */
class BrowseGridAdapter(
    private val onClick: (SankaAnime) -> Unit
) : RecyclerView.Adapter<BrowseGridAdapter.Holder>() {

    private var items: List<SankaAnime> = emptyList()

    fun submitList(list: List<SankaAnime>) {
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
        loadPoster(b.posterViewBrows, anime.posterUrl)
        b.titleViewBrows.text = anime.displayTitle
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Item list Jelajahi (item_browse_list): poster + judul + status + genre.
 * Sanka tidak memberi jumlah episode di ringkasan — baris maxepisode
 * disembunyikan (bukan diisi angka palsu).
 */
class BrowseListAdapter(
    private val onClick: (SankaAnime) -> Unit
) : RecyclerView.Adapter<BrowseListAdapter.Holder>() {

    private var items: List<SankaAnime> = emptyList()

    fun submitList(list: List<SankaAnime>) {
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
        loadPoster(b.posterViewBrows, anime.posterUrl)
        b.titleViewBrows.text = anime.displayTitle
        b.statusText.text = anime.status ?: ""
        b.maxepisode.visibility = View.GONE
        b.genreTextAll.text = anime.genreNames
    }

    override fun getItemCount(): Int = items.size
}

/** Item slider Beranda (item_slide): gambar penuh + judul. */
class SlideAdapter(
    private val onClick: (SankaAnime) -> Unit
) : RecyclerView.Adapter<SlideAdapter.Holder>() {

    private var items: List<SankaAnime> = emptyList()

    fun submitList(list: List<SankaAnime>) {
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
        holder.binding.slideImage.load(anime.posterUrl) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
            crossfade(true)
        }
        holder.binding.slideNameTXT.text = anime.displayTitle
    }

    override fun getItemCount(): Int = items.size
}
