package com.example.moneytracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions", indices = [Index(value = ["smsKey"], unique = true)])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val isCredit: Boolean,
    val category: String,
    val note: String,
    val date: Long,
    val smsKey: String? = null
)

@Entity(tableName = "emis")
data class Emi(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val monthlyAmount: Double,
    val tenureMonths: Int,
    val startDate: Long,
    val paidMonths: Int
)

@Dao
interface AppDao {
    @Query("SELECT * FROM transactions WHERE date BETWEEN :from AND :to ORDER BY date DESC")
    fun txns(from: Long, to: Long): Flow<List<Txn>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: Txn): Long

    @Delete
    suspend fun delete(t: Txn)

    @Query("SELECT * FROM emis ORDER BY id DESC")
    fun emis(): Flow<List<Emi>>

    @Insert
    suspend fun insertEmi(e: Emi)

    @Update
    suspend fun updateEmi(e: Emi)

    @Delete
    suspend fun deleteEmi(e: Emi)
}

@Database(entities = [Txn::class, Emi::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "money.db")
                .build().also { inst = it }
        }
    }
}
