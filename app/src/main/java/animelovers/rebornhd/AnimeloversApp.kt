package animelovers.rebornhd

import android.app.Application
import animelovers.rebornhd.data.api.AnikotoApi
import animelovers.rebornhd.data.api.VidCloudApi
import animelovers.rebornhd.data.auth.AuthRepository
import animelovers.rebornhd.data.auth.StubAuthRepository
import animelovers.rebornhd.data.local.AppDatabase
import animelovers.rebornhd.data.repo.AnimeRepository
import animelovers.rebornhd.data.repo.PlayerRepository
import animelovers.rebornhd.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application — satu tempat membuat dependensi (tanpa DI framework, PRD §3.2).
 *
 * Auth: [authRepository] saat ini [StubAuthRepository] (Firebase belum dipasang).
 * Bila proyek Firebase baru sudah dibuat (docs/firebase-setup.md), ganti
 * satu baris ini ke implementasi Firebase — UI + gerbang tidak berubah.
 */
class AnimeloversApp : Application() {

    /** Scope aplikasi — untuk kerja yang harus selesai walau Activity sudah
     *  mati (mis. simpan posisi tonton di onDestroy player). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: Prefs by lazy { Prefs(this) }
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val anikotoApi: AnikotoApi by lazy { AnikotoApi.create() }
    val vidCloudApi: VidCloudApi by lazy { VidCloudApi() }

    val authRepository: AuthRepository by lazy { StubAuthRepository(this) }

    val animeRepository: AnimeRepository by lazy {
        AnimeRepository(anikotoApi, database)
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(this, vidCloudApi)
    }
}

/** Akses Application dari Activity/Fragment mana pun. */
fun android.content.Context.app(): AnimeloversApp =
    applicationContext as AnimeloversApp
