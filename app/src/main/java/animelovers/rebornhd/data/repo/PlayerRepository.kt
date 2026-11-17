package animelovers.rebornhd.data.repo

import android.content.Context
import android.net.Uri
import animelovers.rebornhd.data.api.SubtitleApi
import animelovers.rebornhd.data.api.VidCloudApi
import animelovers.rebornhd.data.model.VideoSource
import animelovers.rebornhd.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolve satu episode menjadi [VideoSource] yang bisa diputar player.
 *
 * Urutan (PRD §3.5 + Riset T1):
 * 1. Bila backend kecil terpasang ([Constants.BACKEND_BASE_URL] terisi):
 *    GET /resolve → HLS + tracks. Bila gagal, jatuh ke jalur langsung.
 * 2. Jalur langsung: VidCloud getSources server 1 → 2 → 3.
 * 3. Subtitle Indonesia:
 *    a. Track berlabel "Indonesian" dari getSources (native) → pakai langsung.
 *    b. Bila tidak ada dan backend ada → GET /subtitle/id (translate + cache).
 *    c. Bila backend tidak ada/tidak terjangkau → VTT Inggris langsung.
 * 4. JANGAN PERNAH menampilkan track berlabel Indonesia yang isinya Inggris.
 *
 * URL ber-token (?t=) hanya hidup di memori selama pemutaran — TIDAK PERNAH
 * disimpan di DB (PRD §3.5).
 */
class PlayerRepository(
    private val context: Context,
    private val vidCloud: VidCloudApi = VidCloudApi(),
    private val subtitleApi: SubtitleApi? =
        Constants.BACKEND_BASE_URL.ifBlank { null }?.let { SubtitleApi.create(it) }
) {

    val hasBackend: Boolean get() = subtitleApi != null

    /**
     * Resolve episode. [aniId] = ani_id Anikoto (fallback mal_id).
     * @return null bila semua sumber gagal (player menampilkan pesan error).
     */
    suspend fun resolve(aniId: Int, episode: Int): VideoSource? {
        // 1. Backend dulu (bila ada).
        subtitleApi?.let { api ->
            try {
                val resolved = api.resolve(aniId, episode)
                if (resolved.hls.isNotBlank()) {
                    return buildFromBackend(api, aniId, episode, resolved)
                }
            } catch (e: Exception) {
                // Backend gagal = lanjut ke jalur langsung (bukan error fatal).
            }
        }
        // 2. Jalur langsung: VidCloud.
        val sources = vidCloud.getSourcesAnyServer(aniId, episode) ?: return null
        val hls = sources.sources.firstOrNull { it.file.isNotBlank() }?.file
            ?: return null
        return buildFromVidCloud(sources, hls, aniId, episode)
    }

    private suspend fun buildFromBackend(
        api: SubtitleApi,
        aniId: Int,
        episode: Int,
        resolved: animelovers.rebornhd.data.api.ResolveResponse
    ): VideoSource {
        var subtitleIdUri: Uri? = null
        var subtitleIdKind: String? = null
        // Track Indonesia native dari backend (bila /resolve sudah menyediakannya).
        val nativeId = resolved.tracks.firstOrNull {
            it.lang.equals("id", ignoreCase = true) ||
                it.lang.equals("indonesian", ignoreCase = true)
        }
        if (nativeId != null && nativeId.url.isNotBlank()) {
            subtitleIdUri = Uri.parse(nativeId.url)
            subtitleIdKind = "native"
        } else {
            // Minta VTT Indonesia hasil translate + cache.
            try {
                val body = api.subtitleId(aniId, episode)
                val vtt = body.string()
                if (vtt.contains("WEBVTT")) {
                    val file = File(context.cacheDir, "sub_id_${aniId}_$episode.vtt")
                    file.writeText(vtt)
                    subtitleIdUri = Uri.fromFile(file)
                    subtitleIdKind = "translated"
                }
            } catch (e: Exception) {
                // Translate gagal = fallback ke Inggris di bawah.
            }
        }
        val enUrl = resolved.tracks.firstOrNull {
            it.lang.equals("en", ignoreCase = true) ||
                it.lang.equals("english", ignoreCase = true)
        }?.url
        return VideoSource(
            hlsUrl = resolved.hls,
            subtitleIdUri = subtitleIdUri,
            subtitleIdKind = subtitleIdKind,
            subtitleEnUrl = enUrl,
            introStart = resolved.intro?.start ?: 0,
            introEnd = resolved.intro?.end ?: 0,
            outroStart = resolved.outro?.start ?: 0,
            outroEnd = resolved.outro?.end ?: 0
        )
    }

    private suspend fun buildFromVidCloud(
        sources: animelovers.rebornhd.data.model.VidCloudSources,
        hls: String,
        aniId: Int,
        episode: Int
    ): VideoSource {
        // a. Track Indonesia native (sebagian judul simulcast sudah punya).
        val nativeId = sources.tracks.firstOrNull {
            it.label.equals("Indonesian", ignoreCase = true)
        }
        var subtitleIdUri: Uri? = nativeId?.let { Uri.parse(it.file) }
        var subtitleIdKind: String? = if (nativeId != null) "native" else null
        // b. Bila tidak ada track native dan backend ada → translate.
        if (subtitleIdUri == null && subtitleApi != null) {
            try {
                val vtt = subtitleApi.subtitleId(aniId, episode).string()
                if (vtt.contains("WEBVTT")) {
                    val file = File(context.cacheDir, "sub_id_${aniId}_$episode.vtt")
                    file.writeText(vtt)
                    subtitleIdUri = Uri.fromFile(file)
                    subtitleIdKind = "translated"
                }
            } catch (e: Exception) {
                // Abaikan — fallback Inggris di bawah.
            }
        }
        // c. Fallback Inggris (server 1 diutamakan — getSourcesAnyServer
        //    sudah mencoba server 1 dulu; bila ini server 2/3, VTT-nya
        //    bisa 403 dari IP tertentu — player tetap jalan tanpa subtitle
        //    bila unduhan VTT gagal, dan itu ditangani di VideoPlayActivity).
        val enUrl = sources.tracks.firstOrNull {
            it.label.equals("English", ignoreCase = true)
        }?.file
        return VideoSource(
            hlsUrl = hls,
            subtitleIdUri = subtitleIdUri,
            subtitleIdKind = subtitleIdKind,
            subtitleEnUrl = enUrl,
            serverLabel = sources.sources.firstOrNull()?.label ?: "",
            server = sources.server,
            introStart = sources.introStart,
            introEnd = sources.introEnd,
            outroStart = sources.outroStart,
            outroEnd = sources.outroEnd
        )
    }

    /** Cek hidup backend (untuk Splash) — gagal = lanjut saja, bukan error. */
    suspend fun checkBackendHealth(): Boolean {
        val api = subtitleApi ?: return false
        return try {
            withContext(Dispatchers.IO) { api.health() }
            true
        } catch (e: Exception) {
            false
        }
    }
}
