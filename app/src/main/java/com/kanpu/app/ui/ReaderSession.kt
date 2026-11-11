package com.kanpu.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kanpu.app.data.AnnotationEntity
import com.kanpu.app.data.ScoreEntity
import com.kanpu.app.data.ScoreRepository
import com.kanpu.app.domain.AutoTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class AnnotationChange(
    val page: Int,
    val before: List<AnnotationEntity>,
    val after: List<AnnotationEntity>
)

class ReaderSession(score: ScoreEntity, private val repository: ScoreRepository) {
    var defaultDurationSec: Int = score.defaultDurationSec
    var pageDurations: Map<Int, Int> = emptyMap()
    val timer = AutoTurn(score.pageCount) { page ->
        (pageDurations[page] ?: defaultDurationSec).toLong() * 1000
    }
    var marks by mutableStateOf<List<AnnotationEntity>>(emptyList())
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var pendingSaveCount by mutableIntStateOf(0)
        private set
    private val editMutex = Mutex()
    private val pendingPages = linkedMapOf<Int, List<AnnotationEntity>>()
    private val undo = ArrayDeque<AnnotationChange>()
    private val redo = ArrayDeque<AnnotationChange>()

    fun durationSec(page: Int): Int = pageDurations[page] ?: defaultDurationSec
    val hasPendingSaves: Boolean get() = pendingSaveCount > 0

    suspend fun loadMarks(scoreId: String, pages: List<Int>) {
        editMutex.withLock {
            val loaded = pages.flatMap { page -> pendingPages[page] ?: repository.annotations(scoreId, page).first() }
            marks = marks.filterNot { it.pageIndex in pages } + loaded
        }
    }

    suspend fun changePage(scoreId: String, page: Int, after: List<AnnotationEntity>) {
        editMutex.withLock {
            val before = marks.filter { it.pageIndex == page }
            if (before == after) return@withLock
            marks = marks.filterNot { it.pageIndex == page } + after
            undo.addLast(AnnotationChange(page, before, after))
            while (undo.size > 50) undo.removeFirst()
            redo.clear()
            persistPage(scoreId, page, after)
        }
    }

    suspend fun undo(scoreId: String) {
        editMutex.withLock {
            val change = undo.removeLastOrNull() ?: return@withLock
            marks = marks.filterNot { it.pageIndex == change.page } + change.before
            redo.addLast(change)
            persistPage(scoreId, change.page, change.before)
        }
    }

    suspend fun redo(scoreId: String) {
        editMutex.withLock {
            val change = redo.removeLastOrNull() ?: return@withLock
            marks = marks.filterNot { it.pageIndex == change.page } + change.after
            undo.addLast(change)
            persistPage(scoreId, change.page, change.after)
        }
    }

    suspend fun retryPending(scoreId: String) {
        editMutex.withLock {
            pendingPages.toMap().forEach { (page, annotations) ->
                persistPage(scoreId, page, annotations)
            }
        }
    }

    private suspend fun persistPage(scoreId: String, page: Int, annotations: List<AnnotationEntity>) {
        pendingPages[page] = annotations
        pendingSaveCount = pendingPages.size
        try {
            repository.replaceAnnotations(scoreId, page, annotations)
            pendingPages.remove(page)
            pendingSaveCount = pendingPages.size
            if (pendingPages.isEmpty()) saveError = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            saveError = error.message ?: "指法保存失败"
        }
    }

    fun canUndo(): Boolean = undo.isNotEmpty()
    fun canRedo(): Boolean = redo.isNotEmpty()
}
