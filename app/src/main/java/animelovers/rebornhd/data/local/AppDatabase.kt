package animelovers.rebornhd.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity

/**
 * Room di atas SQLite. Nama file DB dipertahankan dari aslinya: "record.db".
 * Skema: PRD §5 (video + favorite).
 *
 * Versi 2 (migrasi DESTRUKTIF — D4, disetujui Faygo 2026-11-17):
 * kunci `idtitle` kini SLUG Sanka (dulu id angka Anikoto); riwayat dan
 * favorit v1 DIHAPUS BERSIH karena kuncinya tidak berlaku lagi.
 * Skema tabel tidak berubah (kolom sama) — yang berubah hanya isi kunci.
 */
@Database(
    entities = [VideoEntity::class, FavoriteEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "record.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
