package animelovers.rebornhd.data.api

import android.util.Base64
import animelovers.rebornhd.data.model.SubtitleTrack
import animelovers.rebornhd.data.model.VidCloudSource
import animelovers.rebornhd.data.model.VidCloudSources
import animelovers.rebornhd.util.Constants
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * VidCloud — sumber video + subtitle Inggris UTAMA (terverifikasi live, Riset T1).
 *
 * `GET /embed-2/v3/e-1/getSources?id={ani_id}-{ep}-{server}-sub` → 200 tanpa
 * key/Referer, CORS `*`. Respons: {"encrypted":true,"data":"<base64url>"}.
 * Decode: base64url → tiap byte XOR `(0x4D + (i % 23)) & 0xff` → JSON:
 * {sources:[{file: master.m3u8?t=…, type:"hls", label:"Sora", default:true}],
 *  tracks:[{label:"English", file: subs.vtt?t=…, default:true}, …],
 *  intro:{start,end}, outro:{start,end}, server:1}
 *
 * Aturan (Riset T1): ambil subtitle dari server 1; bila server 1 tidak ada,
 * fallback ke MegaPlay, BUKAN server 2 (VTT server 2 → 403 dari IP sandbox).
 * URL ber-token (?t=) TIDAK BOLEH disimpan — ambil segar per request.
 */
class VidCloudApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    /**
     * Ambil + decode getSources untuk satu server (1, 2, atau 3).
     * @return null bila request gagal / respons tidak bisa didecode.
     */
    suspend fun getSources(aniId: Int, episode: Int, server: Int): VidCloudSources? =
        withContext(Dispatchers.IO) {
            val url = "${Constants.VIDCLOUD_BASE_URL}embed-2/v3/e-1/" +
                "getSources?id=$aniId-$episode-$server-sub"
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val data = root.get("data")?.asString ?: return@withContext null
                    val json = decodeXor(data) ?: return@withContext null
                    parseSources(json, server)
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Coba server 1 → 2 → 3 berurutan; kembalikan hasil pertama yang berhasil.
     * Server 1 selalu dicoba dulu (aturan subtitle, Riset T1).
     */
    suspend fun getSourcesAnyServer(aniId: Int, episode: Int): VidCloudSources? {
        for (server in 1..3) {
            val result = getSources(aniId, episode, server)
            if (result != null) return result
        }
        return null
    }

    /**
     * Ambil teks mentah dari URL (mis. file VTT) — null bila gagal.
     * Dipakai validasi track "Indonesian" native (lihat PlayerRepository).
     */
    suspend fun fetchText(url: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** base64url (tambah padding bila hilang) → XOR → String JSON. */
    internal fun decodeXor(data: String): String? {
        return try {
            val padded = padBase64(data.trim())
            val bytes = Base64.decode(padded, Base64.URL_SAFE)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) {
                val key = (0x4D + (i % 23)) and 0xFF
                out[i] = (bytes[i].toInt() xor key).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun padBase64(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    private fun parseSources(json: String, server: Int): VidCloudSources? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val sources = root.getAsJsonArray("sources")?.map { el ->
                val o = el.asJsonObject
                VidCloudSource(
                    file = o.get("file")?.asString ?: "",
                    type = o.get("type")?.asString ?: "",
                    label = o.get("label")?.asString ?: "",
                    isDefault = o.get("default")?.asBoolean ?: false
                )
            } ?: emptyList()
            val tracks = root.getAsJsonArray("tracks")?.map { el ->
                val o = el.asJsonObject
                SubtitleTrack(
                    label = o.get("label")?.asString ?: "",
                    file = o.get("file")?.asString ?: "",
                    isDefault = o.get("default")?.asBoolean ?: false
                )
            } ?: emptyList()
            fun range(key: String): Pair<Long, Long> {
                val o = root.getAsJsonObject(key) ?: return 0L to 0L
                val start = o.get("start")?.asLong ?: 0L
                val end = o.get("end")?.asLong ?: 0L
                return start to end
            }
            val (introStart, introEnd) = range("intro")
            val (outroStart, outroEnd) = range("outro")
            VidCloudSources(
                sources = sources,
                tracks = tracks,
                introStart = introStart,
                introEnd = introEnd,
                outroStart = outroStart,
                outroEnd = outroEnd,
                server = root.get("server")?.asInt ?: server
            )
        } catch (e: Exception) {
            null
        }
    }
}
