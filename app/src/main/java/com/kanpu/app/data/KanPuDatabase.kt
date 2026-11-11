package com.kanpu.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scores", indices = [Index(value = ["sha256"], unique = true)])
data class ScoreEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalName: String,
    val privatePath: String,
    val sha256: String,
    val byteSize: Long,
    val pageCount: Int,
    val sourceRelativePath: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0,
    val lastPageIndex: Int = 0,
    val defaultDurationSec: Int = 30,
    val preRollSec: Int = 0,
    val layoutOverride: String? = null,
    val paperOverride: String? = null
)

@Entity(tableName = "score_lists", indices = [Index(value = ["normalizedName"], unique = true)])
data class ScoreListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "score_list_entries",
    primaryKeys = ["listId", "scoreId"],
    foreignKeys = [
        ForeignKey(entity = ScoreListEntity::class, parentColumns = ["id"], childColumns = ["listId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ScoreEntity::class, parentColumns = ["id"], childColumns = ["scoreId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("scoreId"), Index(value = ["listId", "sortOrder"])]
)
data class ScoreListEntryEntity(
    val listId: String,
    val scoreId: String,
    val sortOrder: Long,
    val addedAt: Long
)

data class ScoreListSummary(
    val id: String,
    val name: String,
    val count: Int
)

@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(entity = ScoreEntity::class, parentColumns = ["id"], childColumns = ["scoreId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["scoreId", "pageIndex"])]
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val scoreId: String,
    val pageIndex: Int,
    val u: Float,
    val v: Float,
    val finger: Int,
    val hand: String,
    val color: Int,
    val fontSizeRatio: Float,
    val updatedAt: Long
)

@Entity(
    tableName = "page_timings",
    primaryKeys = ["scoreId", "pageIndex"],
    foreignKeys = [ForeignKey(entity = ScoreEntity::class, parentColumns = ["id"], childColumns = ["scoreId"], onDelete = ForeignKey.CASCADE)]
)
data class PageTimingEntity(val scoreId: String, val pageIndex: Int, val durationSec: Int)

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val sourceUrisJson: String,
    val targetListId: String?,
    val status: String,
    val discoveredCount: Int,
    val completedCount: Int,
    val createdAt: Long
)

@Entity(
    tableName = "import_items",
    foreignKeys = [ForeignKey(entity = ImportJobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")]
)
data class ImportItemEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val sourceUri: String,
    val sourceName: String,
    val relativePath: String,
    val status: String,
    val errorCode: String? = null,
    val scoreId: String? = null,
    val addedToList: Boolean = false
)

@Dao
interface KanPuDao {
    @Query("SELECT * FROM scores ORDER BY CASE WHEN lastOpenedAt = 0 THEN 1 ELSE 0 END, lastOpenedAt DESC, createdAt DESC")
    fun scores(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE id = :id LIMIT 1")
    suspend fun score(id: String): ScoreEntity?

    @Query("SELECT * FROM scores WHERE sha256 = :sha LIMIT 1")
    suspend fun scoreByHash(sha: String): ScoreEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScore(score: ScoreEntity)

    @Query("UPDATE scores SET title = :title WHERE id = :id")
    suspend fun renameScore(id: String, title: String)

    @Query("UPDATE scores SET lastPageIndex = :page, lastOpenedAt = :whenMs WHERE id = :id")
    suspend fun updateReadingProgress(id: String, page: Int, whenMs: Long)

    @Query("UPDATE scores SET defaultDurationSec = :duration WHERE id = :id")
    suspend fun setDefaultDuration(id: String, duration: Int)

    @Query("UPDATE scores SET layoutOverride = :mode WHERE id = :id")
    suspend fun setLayoutOverride(id: String, mode: String?)

    @Query("UPDATE scores SET paperOverride = :mode WHERE id = :id")
    suspend fun setPaperOverride(id: String, mode: String?)

    @Query("DELETE FROM scores WHERE id = :id")
    suspend fun deleteScore(id: String)

    @Query("SELECT * FROM score_lists ORDER BY createdAt DESC")
    fun scoreLists(): Flow<List<ScoreListEntity>>

    @Query("SELECT score_lists.id AS id, score_lists.name AS name, COUNT(score_list_entries.scoreId) AS count FROM score_lists LEFT JOIN score_list_entries ON score_lists.id = score_list_entries.listId GROUP BY score_lists.id ORDER BY score_lists.createdAt DESC")
    fun listSummaries(): Flow<List<ScoreListSummary>>

    @Query("SELECT * FROM score_lists WHERE id = :id LIMIT 1")
    suspend fun scoreList(id: String): ScoreListEntity?

    @Query("SELECT * FROM score_lists WHERE normalizedName = :normalized LIMIT 1")
    suspend fun scoreListByName(normalized: String): ScoreListEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScoreList(list: ScoreListEntity)

    @Query("UPDATE score_lists SET name = :name, normalizedName = :normalized, updatedAt = :whenMs WHERE id = :id")
    suspend fun renameScoreList(id: String, name: String, normalized: String, whenMs: Long)

    @Query("DELETE FROM score_lists WHERE id = :id")
    suspend fun deleteScoreList(id: String)

    @Query("SELECT scores.* FROM scores JOIN score_list_entries ON score_list_entries.scoreId = scores.id WHERE score_list_entries.listId = :listId ORDER BY score_list_entries.sortOrder, score_list_entries.addedAt")
    fun scoresInList(listId: String): Flow<List<ScoreEntity>>

    @Query("SELECT scores.* FROM scores WHERE NOT EXISTS (SELECT 1 FROM score_list_entries WHERE score_list_entries.scoreId = scores.id) ORDER BY scores.createdAt DESC")
    fun uncategorizedScores(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM score_list_entries WHERE listId = :listId ORDER BY sortOrder")
    suspend fun listEntries(listId: String): List<ScoreListEntryEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM score_list_entries WHERE listId = :listId")
    suspend fun nextSortOrder(listId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListEntry(entry: ScoreListEntryEntity): Long

    @Query("DELETE FROM score_list_entries WHERE listId = :listId AND scoreId = :scoreId")
    suspend fun removeListEntry(listId: String, scoreId: String)

    @Query("UPDATE score_list_entries SET sortOrder = :order WHERE listId = :listId AND scoreId = :scoreId")
    suspend fun setListEntryOrder(listId: String, scoreId: String, order: Long)

    @Query("SELECT * FROM annotations WHERE scoreId = :scoreId AND pageIndex = :pageIndex ORDER BY updatedAt, id")
    fun annotations(scoreId: String, pageIndex: Int): Flow<List<AnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteAnnotation(id: String)

    @Query("DELETE FROM annotations WHERE scoreId = :scoreId AND pageIndex = :pageIndex")
    suspend fun clearPageAnnotations(scoreId: String, pageIndex: Int)

    @Query("SELECT * FROM page_timings WHERE scoreId = :scoreId")
    fun timings(scoreId: String): Flow<List<PageTimingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTiming(timing: PageTimingEntity)

    @Query("DELETE FROM page_timings WHERE scoreId = :scoreId AND pageIndex = :pageIndex")
    suspend fun clearTiming(scoreId: String, pageIndex: Int)

    @Query("DELETE FROM page_timings WHERE scoreId = :scoreId")
    suspend fun clearAllTimings(scoreId: String)

    @Upsert
    suspend fun upsertImportJob(job: ImportJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportItem(item: ImportItemEntity)

    @Query("SELECT * FROM import_jobs ORDER BY createdAt DESC LIMIT 10")
    fun recentImportJobs(): Flow<List<ImportJobEntity>>

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY rowid")
    fun importItems(jobId: String): Flow<List<ImportItemEntity>>

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY rowid")
    suspend fun importItemsSnapshot(jobId: String): List<ImportItemEntity>

    @Query("SELECT * FROM import_jobs WHERE id = :jobId LIMIT 1")
    suspend fun importJob(jobId: String): ImportJobEntity?

    @Query("UPDATE import_jobs SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun interruptRunningImports()
}

@Database(
    entities = [ScoreEntity::class, ScoreListEntity::class, ScoreListEntryEntity::class,
        AnnotationEntity::class, PageTimingEntity::class, ImportJobEntity::class, ImportItemEntity::class],
    version = 1,
    exportSchema = true
)
abstract class KanPuDatabase : RoomDatabase() {
    abstract fun dao(): KanPuDao
}
