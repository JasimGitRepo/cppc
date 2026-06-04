package com.example.core.storage

import androidx.room.*

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "header_files")
data class HeaderFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val filepath: String
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insertSession(session: ChatSession): Long

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    suspend fun getAllSessions(): List<ChatSession>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Dao
interface HeaderFileDao {
    @Insert
    suspend fun insertAll(files: List<HeaderFileEntity>)

    @Query("SELECT * FROM header_files WHERE filename = :name")
    suspend fun findByName(name: String): List<HeaderFileEntity>
    
    @Query("SELECT COUNT(*) FROM header_files")
    suspend fun getCount(): Int

    @Query("DELETE FROM header_files")
    suspend fun deleteAll()
}

@Database(entities = [ChatSession::class, ChatMessageEntity::class, HeaderFileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun headerFileDao(): HeaderFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cppc_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
