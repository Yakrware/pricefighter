package com.pricefighter.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One saved price-check report. All history lives only in this on-device table. */
@Entity(tableName = "price_check_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val searchTerm: String,
    val soldCount: Int,
    val minPrice: Double,
    val maxPrice: Double,
    val averagePrice: Double,
    val medianPrice: Double,
    val velocityLast30Days: Int,
    val activeListings: Int,
    val lowestActivePrice: Double?,
    val currency: String,
    val soldDeeplink: String,
    val createdAtEpochMs: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM price_check_history ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM price_check_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM price_check_history")
    suspend fun clear()
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pricefighter.db",
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}
