package com.kanpu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class ImportProgress(
    val jobId: String,
    val scanning: Boolean,
    val discovered: Int,
    val completed: Int,
    val succeeded: Int,
    val duplicated: Int,
    val failed: Int,
    val addedToList: Int,
    val status: String,
    val errors: List<String> = emptyList()
)

private data class Candidate(val uri: Uri, val name: String, val relativePath: String)
private data class Imported(val status: String, val scoreId: String?)

class ImportCoordinator(private val context: Context, private val repository: ScoreRepository) {
    private val dao = repository.dao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress

    private val startupJob = scope.launch {
            dao.interruptRunningImports()
            cleanOrphans()
    }

    fun startFiles(uris: List<Uri>, targetListId: String? = null) = start("FILES", uris, targetListId)
    fun startTree(uri: Uri, targetListId: String? = null) = start("TREE", listOf(uri), targetListId)

    private fun start(kind: String, uris: List<Uri>, targetListId: String?) {
        if (uris.isEmpty() || activeJob?.isActive == true) return
        val id = UUID.randomUUID().toString()
        val array = JSONArray()
        uris.forEach { array.put(it.toString()) }
        val record = ImportJobEntity(id, kind, array.toString(), targetListId, "RUNNING", 0, 0, System.currentTimeMillis())
        activeJob = scope.launch { runImport(record) }
    }

    fun resume(jobId: String) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            val existing = dao.importJob(jobId) ?: return@launch
            val record = existing.copy(status = "RUNNING")
            runImport(record)
        }
    }

    fun cancel() { activeJob?.cancel() }
    fun dismissProgress() { _progress.value = _progress.value?.copy(status = "DISMISSED") }
    fun close() { scope.cancel() }

    private suspend fun runImport(initial: ImportJobEntity) {
        startupJob.join()
        var job = initial
        var p = ImportProgress(job.id, true, 0, 0, 0, 0, 0, 0, "RUNNING")
        try {
            dao.upsertImportJob(job)
            _progress.value = p
            val candidates = if (job.kind == "TREE") {
                scanTree(Uri.parse(JSONArray(job.sourceUrisJson).getString(0))) { count, error ->
                    p = p.copy(discovered = count, errors = error?.let { p.errors + it } ?: p.errors)
                    _progress.value = p
                }
            } else {
                val array = JSONArray(job.sourceUrisJson)
                (0 until array.length()).mapNotNull { index ->
                    val uri = Uri.parse(array.getString(index))
                    val doc = DocumentFile.fromSingleUri(context, uri)
                    val name = doc?.name ?: "未命名.pdf"
                    if (isPdfCandidate(name, doc?.type)) Candidate(uri, name, name) else null
                }
            }
            val previous = dao.importItemsSnapshot(job.id).associateBy { it.sourceUri }
            val allCandidates = (candidates + previous.values.map {
                Candidate(Uri.parse(it.sourceUri), it.sourceName, it.relativePath)
            }).distinctBy { it.uri.toString() }
            val alreadyDone = mutableSetOf<String>()
            var doneSuccess = 0
            var doneDuplicate = 0
            var doneAdded = 0
            for (item in previous.values) {
                if (item.status in setOf("SUCCESS", "DUPLICATE") &&
                    item.scoreId != null && dao.score(item.scoreId) != null) {
                    alreadyDone.add(item.sourceUri)
                    if (item.status == "SUCCESS") doneSuccess++ else doneDuplicate++
                    if (item.addedToList) doneAdded++
                }
            }
            job = job.copy(discoveredCount = allCandidates.size, completedCount = alreadyDone.size)
            dao.upsertImportJob(job)
            p = p.copy(scanning = false, discovered = allCandidates.size, completed = alreadyDone.size,
                succeeded = doneSuccess, duplicated = doneDuplicate, addedToList = doneAdded)
            if (job.targetListId != null && dao.scoreList(job.targetListId) == null) {
                p = p.copy(errors = p.errors + "目标琴谱单已删除；琴谱仍保留在全部琴谱中")
            }
            _progress.value = p
            for (candidate in allCandidates) {
                currentCoroutineContext().ensureActive()
                if (candidate.uri.toString() in alreadyDone) continue
                val item = previous[candidate.uri.toString()]?.copy(status = "RUNNING", errorCode = null,
                    scoreId = null, addedToList = false) ?:
                    ImportItemEntity(UUID.randomUUID().toString(), job.id, candidate.uri.toString(), candidate.name,
                        candidate.relativePath, "RUNNING")
                dao.upsertImportItem(item)
                try {
                    val imported = importOne(candidate)
                    var added = false
                    if (job.targetListId != null && imported.scoreId != null) {
                        added = repository.addToList(job.targetListId, imported.scoreId)
                        if (!added && dao.scoreList(job.targetListId) == null &&
                            p.errors.none { it == "目标琴谱单已删除；琴谱仍保留在全部琴谱中" }) {
                            p = p.copy(errors = p.errors + "目标琴谱单已删除；琴谱仍保留在全部琴谱中")
                        }
                    }
                    dao.upsertImportItem(item.copy(status = imported.status, scoreId = imported.scoreId,
                        addedToList = added))
                    p = when (imported.status) {
                        "SUCCESS" -> p.copy(succeeded = p.succeeded + 1)
                        else -> p.copy(duplicated = p.duplicated + 1)
                    }.copy(addedToList = p.addedToList + if (added) 1 else 0)
                } catch (cancelled: CancellationException) {
                    dao.upsertImportItem(item.copy(status = "UNPROCESSED"))
                    throw cancelled
                } catch (error: Exception) {
                    val reason = errorMessage(error)
                    dao.upsertImportItem(item.copy(status = "FAILED", errorCode = reason))
                    p = p.copy(failed = p.failed + 1, errors = p.errors + "${candidate.relativePath}：$reason")
                }
                p = p.copy(completed = p.completed + 1)
                job = job.copy(completedCount = p.completed)
                dao.upsertImportJob(job)
                _progress.value = p
            }
            job = job.copy(status = "COMPLETED")
            dao.upsertImportJob(job)
            _progress.value = p.copy(status = "COMPLETED")
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                dao.upsertImportJob(job.copy(status = "CANCELLED"))
                _progress.value = p.copy(status = "CANCELLED")
            }
        } catch (error: Exception) {
            val reason = errorMessage(error)
            dao.upsertImportJob(job.copy(status = "INTERRUPTED"))
            _progress.value = p.copy(status = "INTERRUPTED", errors = p.errors + reason)
        }
    }

    private suspend fun scanTree(uri: Uri, report: (Int, String?) -> Unit): List<Candidate> {
        val root = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("无法打开所选文件夹")
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        val seen = HashSet<String>()
        val found = ArrayList<Candidate>()
        queue.add(root to "")
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, relative) = queue.removeFirst()
            if (!seen.add(directory.uri.toString())) continue
            if (!directory.canRead()) {
                report(found.size, "${relative.ifBlank { "所选文件夹" }}：无法读取")
                continue
            }
            val children = try { directory.listFiles() } catch (_: Exception) {
                report(found.size, "${relative.ifBlank { "所选文件夹" }}：无法读取")
                continue
            }
            for (child in children) {
                val name = child.name ?: continue
                val path = if (relative.isBlank()) name else "$relative/$name"
                if (child.isDirectory) queue.add(child to path)
                else if (child.isFile && isPdfCandidate(name, child.type)) {
                    found.add(Candidate(child.uri, name, path))
                    report(found.size, null)
                }
            }
        }
        return found
    }

    private fun isPdfCandidate(name: String, mime: String?): Boolean =
        name.endsWith(".pdf", ignoreCase = true) || mime == "application/pdf"

    private suspend fun importOne(candidate: Candidate): Imported {
        val files = File(context.filesDir, "scores").also { it.mkdirs() }
        val tempDir = File(context.filesDir, "imports").also { it.mkdirs() }
        val id = UUID.randomUUID().toString()
        val temporary = File(tempDir, "$id.tmp")
        val destination = File(files, "$id.pdf")
        var thumb: File? = null
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.contentResolver.openInputStream(candidate.uri).use { input ->
                if (input == null) throw IOException("无法读取文件")
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytes += read
                    }
                }
            }
            val header = ByteArray(5)
            val isPdf = bytes >= 5 && DataInputStream(FileInputStream(temporary)).use {
                it.readFully(header)
                String(header, Charsets.US_ASCII) == "%PDF-"
            }
            if (!isPdf) {
                throw IOException("不是有效 PDF 文件")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = dao.scoreByHash(hash)
            if (existing != null) return Imported("DUPLICATE", existing.id)
            val pageCount: Int
            val thumbDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
            val thumbnail = File(thumbDir, "$id.png")
            thumb = thumbnail
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount < 1) throw IOException("PDF 没有可阅读页面")
                    renderer.openPage(0).use { first ->
                        val width = 240
                        val height = (width.toFloat() * first.height / first.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            first.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            thumbnail.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 85, it) }
                        } finally { bitmap.recycle() }
                    }
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("保存琴谱失败")
            try {
                val score = ScoreEntity(id, candidate.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    candidate.name, destination.absolutePath, hash, bytes, pageCount,
                    candidate.relativePath, System.currentTimeMillis())
                dao.insertScore(score)
            } catch (error: Exception) {
                destination.delete()
                thumbnail.delete()
                throw error
            }
            committed = true
            return Imported("SUCCESS", id)
        } finally {
            temporary.delete()
            if (!committed) thumb?.delete()
        }
    }

    private suspend fun cleanOrphans() {
        File(context.filesDir, "imports").listFiles()?.forEach { if (it.isFile) it.delete() }
        File(context.filesDir, "scores").listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("pdf", true)) {
                val id = file.nameWithoutExtension
                if (dao.score(id) == null) file.delete()
            }
        }
        File(context.cacheDir, "thumbnails").listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("png", true) && dao.score(file.nameWithoutExtension) == null) {
                file.delete()
            }
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is SecurityException -> "需要密码、授权失效或安全方案不受支持"
        is IOException -> error.message ?: "文件读取失败"
        else -> error.message?.take(100) ?: "导入失败"
    }
}
