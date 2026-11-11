package com.kanpu.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class ScoreRepository(private val context: Context, val database: KanPuDatabase) {
    val dao: KanPuDao = database.dao()
    val covers = CoverStore(context, dao)

    val allScores: Flow<List<ScoreEntity>> = dao.scores()
    val lists: Flow<List<ScoreListSummary>> = dao.listSummaries()
    val uncategorized: Flow<List<ScoreEntity>> = dao.uncategorizedScores()

    fun inList(id: String): Flow<List<ScoreEntity>> = dao.scoresInList(id)
    fun annotations(scoreId: String, page: Int): Flow<List<AnnotationEntity>> = dao.annotations(scoreId, page)
    fun timings(scoreId: String): Flow<List<PageTimingEntity>> = dao.timings(scoreId)

    suspend fun createList(name: String): String = withContext(Dispatchers.IO) {
        val clean = validatedListName(name)
        val normalized = clean.lowercase(Locale.ROOT)
        require(dao.scoreListByName(normalized) == null) { "琴谱单名称已存在" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertScoreList(ScoreListEntity(id, clean, normalized, now, now))
        id
    }

    suspend fun renameList(id: String, name: String) = withContext(Dispatchers.IO) {
        val clean = validatedListName(name)
        val normalized = clean.lowercase(Locale.ROOT)
        val existing = dao.scoreListByName(normalized)
        require(existing == null || existing.id == id) { "琴谱单名称已存在" }
        dao.renameScoreList(id, clean, normalized, System.currentTimeMillis())
    }

    suspend fun deleteList(id: String) = withContext(Dispatchers.IO) {
        dao.deleteScoreList(id)
        covers.listFile(id).delete()
    }

    suspend fun addToList(listId: String, scoreId: String): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (dao.scoreList(listId) == null || dao.score(scoreId) == null) return@withTransaction false
            dao.insertListEntry(ScoreListEntryEntity(listId, scoreId, dao.nextSortOrder(listId), System.currentTimeMillis())) != -1L
        }
    }

    suspend fun removeFromList(listId: String, scoreId: String) = withContext(Dispatchers.IO) {
        dao.removeListEntry(listId, scoreId)
    }

    suspend fun reorderList(listId: String, scoreIds: List<String>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = dao.listEntries(listId).map { it.scoreId }
            require(existing.toSet() == scoreIds.toSet() && existing.size == scoreIds.size) { "琴谱单成员已变化，请重新排序" }
            scoreIds.forEachIndexed { index, id -> dao.setListEntryOrder(listId, id, index.toLong()) }
        }
    }

    suspend fun deleteScore(id: String) = withContext(Dispatchers.IO) {
        val score = dao.score(id) ?: return@withContext
        dao.deleteScore(id)
        val file = File(score.privatePath)
        val scoreDir = File(context.filesDir, "scores").canonicalFile
        if (file.canonicalFile.parentFile == scoreDir) file.delete()
        File(context.cacheDir, "thumbnails/$id.png").delete()
        covers.scoreFile(id).delete()
    }

    suspend fun updateProgress(id: String, page: Int) = withContext(Dispatchers.IO) {
        dao.updateReadingProgress(id, page, System.currentTimeMillis())
    }

    suspend fun setDefaultDuration(id: String, seconds: Int) {
        require(seconds in 1..3600)
        dao.setDefaultDuration(id, seconds)
    }

    suspend fun setPageDuration(id: String, page: Int, seconds: Int?) {
        require(page >= 0)
        if (seconds == null) dao.clearTiming(id, page)
        else {
            require(seconds in 1..3600)
            dao.upsertTiming(PageTimingEntity(id, page, seconds))
        }
    }

    suspend fun setPageDurations(id: String, secondsByPage: List<Int>) {
        require(secondsByPage.isNotEmpty() && secondsByPage.all { it in 1..3600 })
        database.withTransaction {
            val score = dao.score(id) ?: error("琴谱不存在")
            require(secondsByPage.size == score.pageCount) { "琴谱页数已变化，请重新打开" }
            secondsByPage.forEachIndexed { page, seconds ->
                dao.upsertTiming(PageTimingEntity(id, page, seconds))
            }
        }
    }

    suspend fun setPageDurationRange(id: String, first: Int, last: Int, seconds: Int) {
        require(first >= 0 && last >= first && seconds in 1..3600)
        database.withTransaction {
            for (page in first..last) dao.upsertTiming(PageTimingEntity(id, page, seconds))
        }
    }

    suspend fun replaceAnnotations(scoreId: String, page: Int, annotations: List<AnnotationEntity>) =
        database.withTransaction {
            dao.clearPageAnnotations(scoreId, page)
            annotations.forEach { dao.upsertAnnotation(it) }
        }

    private fun validatedListName(value: String): String {
        val clean = value.trim()
        require(clean.length in 1..40) { "琴谱单名称需要 1—40 个字符" }
        return clean
    }
}
