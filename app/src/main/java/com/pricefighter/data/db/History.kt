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
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/** One sold listing that fed a report's statistics — kept so the estimate can be inspected. */
data class IncludedListing(
    val title: String,
    val price: Double,
    val soldDateIso: String?,
    val itemUrl: String,
)

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
    /** The individual sold listings the statistics were computed from (for inspection/debugging). */
    val included: List<IncludedListing> = emptyList(),
)

/** Serializes the included-listings list into a single text column (no JSON dependency). */
class HistoryConverters {
    @TypeConverter
    fun encode(list: List<IncludedListing>): String =
        list.joinToString(RECORD_SEP) { item ->
            listOf(
                item.title.clean(),
                item.price.toString(),
                item.soldDateIso.orEmpty(),
                item.itemUrl.clean(),
            ).joinToString(UNIT_SEP)
        }

    @TypeConverter
    fun decode(encoded: String): List<IncludedListing> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(UNIT_SEP)
            if (f.size < 4) return@mapNotNull null
            val price = f[1].toDoubleOrNull() ?: return@mapNotNull null
            IncludedListing(f[0], price, f[2].ifBlank { null }, f[3])
        }
    }

    private fun String.clean() = replace(RECORD_SEP, " ").replace(UNIT_SEP, " ")

    private companion object {
        // ASCII record (0x1E) / unit (0x1F) separators — never present in listing titles or URLs.
        const val RECORD_SEP = ""
        const val UNIT_SEP = ""
    }
}

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

@Database(entities = [HistoryEntity::class], version = 2, exportSchema = false)
@TypeConverters(HistoryConverters::class)
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
