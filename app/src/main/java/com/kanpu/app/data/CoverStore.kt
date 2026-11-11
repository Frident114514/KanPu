package com.kanpu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** User-selected covers live with app data, independently of disposable thumbnails. */
class CoverStore(private val context: Context, private val dao: KanPuDao) {
    fun scoreFile(id: String): File = File(context.filesDir, "covers/scores/$id.jpg")
    fun listFile(id: String): File = File(context.filesDir, "covers/lists/$id.jpg")

    suspend fun setScoreImage(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(dao.score(id) != null) { "琴谱不存在" }
        save(decodeImage(uri), scoreFile(id))
    }

    suspend fun setListImage(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(dao.scoreList(id) != null) { "琴谱单不存在" }
        save(decodeImage(uri), listFile(id))
    }

    suspend fun setScorePage(id: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        val score = dao.score(id) ?: error("琴谱不存在")
        require(pageIndex in 0 until score.pageCount) { "页码超出范围" }
        val pdf = File(score.privatePath)
        val bitmap = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val width = 1000
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 1800)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        try { page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        catch (error: Exception) { recycle(); throw error }
                    }
                }
            }
        }
        save(bitmap, scoreFile(id))
    }

    suspend fun clearScore(id: String) = withContext(Dispatchers.IO) { scoreFile(id).delete() }
    suspend fun clearList(id: String) = withContext(Dispatchers.IO) { listFile(id).delete() }

    private fun decodeImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取图片")
    }

    private fun save(bitmap: Bitmap, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile(destination.name, ".tmp", destination.parentFile)
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            FileOutputStream(temp).use { output ->
                check(flat.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "封面保存失败" }
            }
            check(temp.renameTo(destination)) { "封面保存失败" }
        } finally {
            flat.recycle()
            bitmap.recycle()
            temp.delete()
        }
    }
}
