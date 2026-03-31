package com.example.mydevice.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mydevice.data.local.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE companyId = :companyId AND isActive = 1")
    fun getUsersByCompany(companyId: Int): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE pin = :pin AND companyId = :companyId LIMIT 1")
    suspend fun findByPin(pin: String, companyId: Int): UserEntity?

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun findById(userId: Int): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<UserEntity>)

    @Query("DELETE FROM users WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Int)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}
