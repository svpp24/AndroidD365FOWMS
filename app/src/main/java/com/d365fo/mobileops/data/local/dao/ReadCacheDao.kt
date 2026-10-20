package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d365fo.mobileops.data.local.entity.ReadCacheEntity

/**
 * Blocking, not suspend: the only caller is an OkHttp interceptor, which already runs on
 * OkHttp's own background threads.
 */
@Dao
interface ReadCacheDao {

    @Query("SELECT * FROM offline_read_cache WHERE `key` = :key")
    fun find(key: String): ReadCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(entry: ReadCacheEntity)

    @Query("DELETE FROM offline_read_cache WHERE savedAt < :before")
    fun deleteOlderThan(before: Long): Int

    /** Keeps the newest [keep] entries. */
    @Query(
        "DELETE FROM offline_read_cache WHERE `key` NOT IN " +
            "(SELECT `key` FROM offline_read_cache ORDER BY savedAt DESC LIMIT :keep)",
    )
    fun trimTo(keep: Int): Int

    @Query("DELETE FROM offline_read_cache")
    fun clear()
}
