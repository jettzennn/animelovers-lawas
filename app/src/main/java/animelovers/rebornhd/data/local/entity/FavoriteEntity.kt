package animelovers.rebornhd.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabel `favorite` — BARU (PRD §5.2). Perbaikan atas aslinya yang mencampur
 * favorit ke dalam tabel riwayat: favorit punya daftar sendiri dan tampil
 * sebagai seksi "Favorit" di atas daftar riwayat (tab Riwayat).
 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey
    val idtitle: String,
    val title: String,
    val idposter: String,
    /** Waktu ditandai favorit (epoch ms). */
    val waktu: Long
)
