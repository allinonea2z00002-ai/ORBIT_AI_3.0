package com.orbit.ai.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ---------- Entities ----------

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,              // "user" or "assistant"
    val content: String,
    val engine: String? = null,    // "ONLINE", "OFFLINE" or null for user messages
    val createdAt: Long = System.currentTimeMillis()
)

/** A fact the user explicitly approved ORBIT to remember. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_log")
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val result: String,
    val success: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

/** User-defined phrase -> action mapping, e.g. "study mode" -> "start timer 45". */
@Entity(tableName = "custom_commands")
data class CustomCommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phrase: String,
    val actionText: String
)

/** A daily routine: a notification at a fixed time with the user's own text. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val text: String
)

// ---------- DAOs ----------

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT DISTINCT c.* FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id " +
            "WHERE c.title LIKE '%' || :q || '%' OR m.content LIKE '%' || :q || '%' ORDER BY c.updatedAt DESC"
    )
    fun search(q: String): Flow<List<ConversationEntity>>

    @Insert suspend fun insert(c: ConversationEntity): Long
    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM conversations") suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC, id ASC")
    fun observe(cid: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(cid: Long): List<MessageEntity>

    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("DELETE FROM messages WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>
    @Insert suspend fun insert(m: MemoryEntity): Long
    @Update suspend fun update(m: MemoryEntity)
    @Delete suspend fun delete(m: MemoryEntity)
    @Query("DELETE FROM memories") suspend fun deleteAll()
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_log ORDER BY createdAt DESC LIMIT 100")
    fun observeLog(): Flow<List<AutomationLogEntity>>
    @Insert suspend fun insertLog(l: AutomationLogEntity)
    @Query("DELETE FROM automation_log") suspend fun clearLog()

    @Query("SELECT * FROM custom_commands ORDER BY id DESC")
    fun observeCommands(): Flow<List<CustomCommandEntity>>
    @Query("SELECT * FROM custom_commands")
    suspend fun getCommands(): List<CustomCommandEntity>
    @Insert suspend fun insertCommand(c: CustomCommandEntity)
    @Delete suspend fun deleteCommand(c: CustomCommandEntity)

    @Query("SELECT * FROM routines ORDER BY hour, minute")
    fun observeRoutines(): Flow<List<RoutineEntity>>
    @Insert suspend fun insertRoutine(r: RoutineEntity): Long
    @Delete suspend fun deleteRoutine(r: RoutineEntity)
}

// ---------- Database ----------

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, MemoryEntity::class,
        AutomationLogEntity::class, CustomCommandEntity::class, RoutineEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun automation(): AutomationDao

    companion object {
        fun create(context: Context): OrbitDatabase =
            Room.databaseBuilder(context.applicationContext, OrbitDatabase::class.java, "orbit.db").build()
    }
}
