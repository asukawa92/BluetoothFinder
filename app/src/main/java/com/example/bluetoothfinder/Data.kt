package com.example.bluetoothfinder

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import android.location.Location

@Entity(tableName = "disconnects")
data class Disconnect(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val name: String,
    val time: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val locationTime: Long?,
    val connectionLatitude: Double? = null,
    val connectionLongitude: Double? = null,
    val connectionAccuracy: Float? = null,
    val connectionTime: Long? = null
)

@Dao interface DisconnectDao {
    @Query("SELECT * FROM disconnects ORDER BY time DESC, id DESC") fun observe(): Flow<List<Disconnect>>
    @Insert suspend fun insert(item: Disconnect)
    @Query("DELETE FROM disconnects WHERE id NOT IN (SELECT id FROM disconnects ORDER BY time DESC, id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}

@Database(entities = [Disconnect::class], version = 2, exportSchema = false)
abstract class FinderDb : RoomDatabase() {
    abstract fun dao(): DisconnectDao
    companion object {
        @Volatile private var instance: FinderDb? = null
        private val migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE disconnects ADD COLUMN connectionLatitude REAL")
                db.execSQL("ALTER TABLE disconnects ADD COLUMN connectionLongitude REAL")
                db.execSQL("ALTER TABLE disconnects ADD COLUMN connectionAccuracy REAL")
                db.execSQL("ALTER TABLE disconnects ADD COLUMN connectionTime INTEGER")
            }
        }
        fun get(context: Context): FinderDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinderDb::class.java, "finder.db").addMigrations(migration).build().also { instance = it }
        }
    }
}

object Tracked {
    private const val KEY = "addresses"
    fun get(context: Context): Set<String> = context.getSharedPreferences("tracked", 0).getStringSet(KEY, emptySet())!!.toSet()
    fun set(context: Context, addresses: Set<String>) {
        context.getSharedPreferences("tracked", 0).edit().putStringSet(KEY, addresses).apply()
    }
}

object HistoryRetention {
    private const val DEFAULT_COUNT = 5
    private const val MAX_COUNT = 10_000
    private fun prefs(context: Context) = context.getSharedPreferences("history_retention", 0)
    fun get(context: Context): Int = prefs(context).getInt("count", DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
    fun set(context: Context, count: Int) {
        require(count in 1..MAX_COUNT)
        prefs(context).edit().putInt("count", count).apply()
    }
}

object LastPhoneLocation {
    fun save(context: Context, location: Location) {
        context.getSharedPreferences("last_phone_location", 0).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat("accuracy", location.accuracy)
            .putLong("time", location.time).apply()
    }
    fun get(context: Context): Location? {
        val prefs = context.getSharedPreferences("last_phone_location", 0)
        if (!prefs.contains("time")) return null
        return Location("saved").apply {
            latitude = java.lang.Double.longBitsToDouble(prefs.getLong("lat", 0))
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong("lon", 0))
            accuracy = prefs.getFloat("accuracy", 0f)
            time = prefs.getLong("time", 0)
        }
    }
}

object LastConnectionLocation {
    private fun prefs(context: Context) = context.getSharedPreferences("last_connections", 0)
    fun save(context: Context, address: String, location: Location) {
        prefs(context).edit().putLong("$address:lat", java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong("$address:lon", java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat("$address:accuracy", location.accuracy)
            .putLong("$address:time", location.time).apply()
    }
    fun get(context: Context, address: String): Location? {
        val data = prefs(context)
        if (!data.contains("$address:time")) return null
        return Location("connection").apply {
            latitude = java.lang.Double.longBitsToDouble(data.getLong("$address:lat", 0))
            longitude = java.lang.Double.longBitsToDouble(data.getLong("$address:lon", 0))
            accuracy = data.getFloat("$address:accuracy", 0f)
            time = data.getLong("$address:time", 0)
        }
    }
    fun clear(context: Context, address: String) {
        prefs(context).edit().remove("$address:lat").remove("$address:lon")
            .remove("$address:accuracy").remove("$address:time").apply()
    }
}
