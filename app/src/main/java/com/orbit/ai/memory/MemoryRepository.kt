package com.orbit.ai.memory

import com.orbit.ai.database.MemoryDao
import com.orbit.ai.database.MemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Personal memory. Nothing is saved automatically: an entry exists only because the user
 * typed it in the Memory screen, tapped "Remember" on a message and confirmed, or said
 * "remember that ...". Entries live in the local database and, when "use memory in prompts"
 * is on, are sent along with each online request so the AI can use them.
 */
class MemoryRepository(private val dao: MemoryDao) {
    val all: Flow<List<MemoryEntity>> = dao.observeAll()
    suspend fun texts(): List<String> = dao.getAll().map { it.text }
    suspend fun add(text: String) { if (text.isNotBlank()) dao.insert(MemoryEntity(text = text.trim())) }
    suspend fun edit(m: MemoryEntity, newText: String) { dao.update(m.copy(text = newText.trim())) }
    suspend fun delete(m: MemoryEntity) = dao.delete(m)
    suspend fun deleteAll() = dao.deleteAll()
}
