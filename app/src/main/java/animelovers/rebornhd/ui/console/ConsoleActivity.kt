package animelovers.rebornhd.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.databinding.ActivityConsoleBinding
import animelovers.rebornhd.databinding.ItemConsoleLineBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.setTintedNavigationIcon
import animelovers.rebornhd.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Konsol Diagnostik (v2.0.9 item 6) — layar yang menampilkan baris log
 * aplikasi (waktu + apa yang terjadi), bisa di-scroll seperti konsol
 * sungguhan. Baris baru masuk live lewat [DiagLogger.linesFlow].
 *
 * Warna baris dari level (E/W/I) lewat atribut tema — sadar Dark/Light
 * (lihat attrs.xml + themes.xml). Log TIDAK PERNAH terkirim otomatis;
 * keluar dari HP hanya lewat tombol Bagikan/Simpan (item 7).
 */
class ConsoleActivity : BaseActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private lateinit var adapter: ConsoleLineAdapter
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarConsole.title = getString(R.string.konsol_judul)
        binding.toolbarConsole.setTintedNavigationIcon(R.drawable.ic_arrow_back_black_24dp)
        binding.toolbarConsole.setNavigationOnClickListener { finish() }

        adapter = ConsoleLineAdapter()
        binding.consoleList.apply {
            layoutManager = LinearLayoutManager(this@ConsoleActivity)
            adapter = this@ConsoleActivity.adapter
        }

        binding.consoleFollowSwitch.isChecked = true
        binding.consoleFollowSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoScroll = isChecked
            if (isChecked) scrollToBottom()
        }

        binding.consoleShareButton.setOnClickListener { shareLog() }
        binding.consoleSaveButton.setOnClickListener { saveLog() }
        binding.consoleCopyButton.setOnClickListener { copyLog() }
        binding.consoleClearButton.setOnClickListener { clearLog() }

        lifecycleScope.launch {
            app().diagLogger.linesFlow.collect { lines ->
                adapter.lines = lines
                binding.consoleEmpty.visibility =
                    if (lines.isEmpty()) View.VISIBLE else View.GONE
                if (autoScroll) scrollToBottom()
            }
        }
        app().diagLogger.info(DiagLog.TAG_KONSOL, "Konsol dibuka")
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) {
            binding.consoleList.scrollToPosition(count - 1)
        }
    }

    /** Bagikan file log lewat chooser sistem (WhatsApp/Telegram/dll. —
     *  TIDAK PERNAH hardcode target). */
    private fun shareLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = app().diagLogger.exportSnapshot()
                val uri = FileProvider.getUriForFile(
                    this@ConsoleActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.bagikan_log))
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.bagikan_log_gagal))
                }
            }
        }
    }

    /** Simpan file log ke folder Downloads (dua jalur — lihat
     *  [saveExportToDownloads]). */
    private fun saveLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = app().diagLogger.exportSnapshot()
                saveExportToDownloads(file)
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.log_disimpan))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // String gagal dipakai ulang (unduhan_gagal) — JANGAN
                    // buat string gagal baru (PRD item 7e).
                    toast(getString(R.string.unduhan_gagal))
                }
            }
        }
    }

    /**
     * Salin file export ke folder Downloads — DUA JALUR:
     * - API 29+ (MediaStore, RELATIVE_PATH + IS_PENDING): tanpa izin tulis.
     * - API 21–28 (tulis langsung ke Environment.DIRECTORY_DOWNLOADS):
     *   HP Faygo API 28 — jalur ini WAJIB jalan; izin
     *   WRITE_EXTERNAL_STORAGE (maxSdkVersion 28) SUDAH ada di manifest,
     *   tidak ada izin baru.
     */
    private fun saveExportToDownloads(exportFile: File): String {
        val name = exportFile.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw IOException("MediaStore gagal membuat entri")
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(exportFile).use { input -> input.copyTo(out) }
            } ?: throw IOException("MediaStore gagal membuka output")
            val done = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.update(uri, done, null, null)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val dest = File(downloadsDir, name)
            FileInputStream(exportFile).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return name
    }

    /** Salin semua baris log ke clipboard (teks biasa). */
    private fun copyLog() {
        val lines = app().diagLogger.snapshot()
        val text = lines.joinToString("\n")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AnimeLovers log", text))
        toast(getString(R.string.log_disalin))
    }

    /** Kosongkan log (memori + file) — TANPA dialog konfirmasi (keputusan PRD). */
    private fun clearLog() {
        app().diagLogger.clear()
        toast(getString(R.string.log_dibersihkan))
    }

    companion object {
        /** Buka Konsol dari mana pun (pola DetailActivity.start). */
        fun start(context: Context) {
            context.startActivity(Intent(context, ConsoleActivity::class.java))
        }
    }
}

/**
 * Adapter baris Konsol — satu TextView monospace per baris; warna teks
 * dari level baris (E/W/I) lewat atribut tema (sadar Dark/Light).
 */
private class ConsoleLineAdapter : RecyclerView.Adapter<ConsoleLineAdapter.ViewHolder>() {

    var lines: List<String> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(val binding: ItemConsoleLineBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConsoleLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.binding.lineText.text = line
        holder.binding.lineText.setTextColor(levelColor(holder.itemView.context, line))
    }

    override fun getItemCount(): Int = lines.size

    /** Warna level: E → merah, W → kuning/oranye, I → warna teks biasa. */
    private fun levelColor(context: Context, line: String): Int {
        val attr = when (levelOf(line)) {
            'E' -> R.attr.logErrorColor
            'W' -> R.attr.logWarnColor
            else -> R.attr.logInfoColor
        }
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /** Level dari format baris `... | <LEVEL> | ...` — 'I' bila tak dikenali. */
    private fun levelOf(line: String): Char {
        val parts = line.split(" | ")
        if (parts.size < 3) return 'I'
        val level = parts[1].trim().firstOrNull()
        return if (level == 'E' || level == 'W' || level == 'I') level else 'I'
    }
}
