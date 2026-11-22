package com.mobilechat.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val providerId: String,
    val model: String, val updatedAt: Long = System.currentTimeMillis(), val reasoningEffort: String? = null)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ConversationEntity::class,
    parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId")])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val role: String,
    val text: String = "", val position: Long, val status: String = "completed", val error: String? = null,
    val replacesId: String? = null, val inputTokens: Int? = null, val outputTokens: Int? = null, val modelName: String? = null,
    @ColumnInfo(defaultValue = "''") val reasoning: String = "")

@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = MessageEntity::class,
    parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("messageId")])
data class AttachmentEntity(@PrimaryKey val id: String, val messageId: String, val name: String,
    val mime: String, val kind: String, val size: Long)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY position, id") fun observeMessages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT attachments.* FROM attachments INNER JOIN messages ON messages.id=attachments.messageId WHERE messages.conversationId=:id")
    fun observeAttachments(id: String): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY position, id") suspend fun messages(id: String): List<MessageEntity>
    @Query("SELECT * FROM attachments WHERE messageId=:id") suspend fun attachments(id: String): List<AttachmentEntity>
    @Query("SELECT * FROM conversations WHERE id=:id") suspend fun conversation(id: String): ConversationEntity?
    @Query("SELECT id FROM attachments") suspend fun fileIds(): List<String>
    @Upsert suspend fun saveConversation(value: ConversationEntity)
    @Insert suspend fun insertMessage(value: MessageEntity)
    @Update suspend fun updateMessage(value: MessageEntity)
    @Insert suspend fun insertAttachments(values: List<AttachmentEntity>)
    @Query("DELETE FROM conversations WHERE id=:id") suspend fun deleteConversation(id: String)
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: String)
    @Query("UPDATE messages SET status='interrupted', error='上次生成因进程退出而中断，可重新生成' WHERE status='generating'") suspend fun markInterrupted()
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class], version = 2, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, ChatDatabase::class.java, "mobilechat.db")
            .addMigrations(MIGRATION_1_2).build()
    }
}
