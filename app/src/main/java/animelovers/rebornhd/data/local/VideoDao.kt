package animelovers.rebornhd.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import animelovers.rebornhd.data.local.entity.VideoEntity

@Dao
interface VideoDao {

    @Query("SELECT * FROM video WHERE idtitle = :idtitle LIMIT 1")
    suspend fun getByTitle(idtitle: String): VideoEntity?

    @Query("SELECT * FROM video ORDER BY waktu DESC")
    suspend fun getAll(): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: VideoEntity): Long

    @Update
    suspend fun update(video: VideoEntity)

    @Query("DELETE FROM video WHERE idtitle = :idtitle")
    suspend fun deleteByTitle(idtitle: String)

    @Query("DELETE FROM video WHERE idui = :idui")
    suspend fun deleteById(idui: Int)
}
