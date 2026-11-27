package com.cloudwhile.tscc.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val address: String,
    val port: Int = 9987,
    val nickname: String,
    val serverPassword: String = "",
    val defaultChannel: String = "",
    val channelPassword: String = "",
    val identityId: Long? = null,
    val lastConnectedAt: Long = 0L
)

@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val uniqueId: String,
    val publicKeyBase64: String,
    val iniPayload: String,
    val securityLevel: Int,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_audio_prefs")
data class UserAudioPrefEntity(
    @PrimaryKey val clientUid: String,
    val nicknameHint: String = "",
    val volumePercent: Int = 100, // 0..200
    val localMuted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY lastConnectedAt DESC, id DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY lastConnectedAt DESC, id ASC")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun markConnected(id: Long, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY isDefault DESC, securityLevel DESC, id ASC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities ORDER BY isDefault DESC, id ASC")
    suspend fun getAll(): List<IdentityEntity>

    @Query("SELECT * FROM identities WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IdentityEntity?

    @Query("SELECT * FROM identities WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: IdentityEntity): Long

    @Update
    suspend fun update(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)

    @Query("UPDATE identities SET isDefault = CASE WHEN id = :targetId THEN 1 ELSE 0 END")
    suspend fun setDefaultIdentity(targetId: Long)
}

@Dao
interface UserAudioPrefDao {
    @Query("SELECT * FROM user_audio_prefs")
    fun observeAll(): Flow<List<UserAudioPrefEntity>>

    @Query("SELECT * FROM user_audio_prefs ORDER BY updatedAt DESC")
    suspend fun getAll(): List<UserAudioPrefEntity>

    @Query("SELECT * FROM user_audio_prefs WHERE clientUid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): UserAudioPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: UserAudioPrefEntity)
}

@Database(
    entities = [
        BookmarkEntity::class,
        IdentityEntity::class,
        UserAudioPrefEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun identityDao(): IdentityDao
    abstract fun userAudioPrefDao(): UserAudioPrefDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tscc.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
