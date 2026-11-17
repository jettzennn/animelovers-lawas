package animelovers.rebornhd.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabel `video` — cermin persis record.db aslinya (PRD §5.1).
 *
 * PERINGATAN NAMA: kolom `idsecond` menyimpan posisi tonton dalam MILIDETIK
 * (bukan detik) — namanya dipertahankan dari aslinya supaya skema tetap
 * 1:1; jangan "memperbaiki" namanya tanpa migrasi.
 */
@Entity(tableName = "video")
data class VideoEntity(
    /** ID anime (dari Anikoto series/{id}). */
    val idtitle: String,
    val title: String,
    val idposter: String,
    /** Nomor episode terakhir ditonton. */
    val idepisode: String,
    /** Posisi tonton dalam MILIDETIK. */
    val idsecond: Long,
    @PrimaryKey(autoGenerate = true)
    val idui: Int = 0,
    /** Waktu terakhir ditonton (epoch ms) — urutan History ORDER BY waktu DESC. */
    val waktu: Long
)
