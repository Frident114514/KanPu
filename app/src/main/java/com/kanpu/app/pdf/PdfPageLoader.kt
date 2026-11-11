package com.kanpu.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.kanpu.app.domain.VisiblePages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

data class PageImage(val page: Int, val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

class PdfPageLoader(private val file: File) {
    private val maxHeapBytes = Runtime.getRuntime().maxMemory()
    private val maxRenderWidth = if (maxHeapBytes <= 192L * 1024 * 1024) 1600 else 1800
    private val cacheBytes = (maxHeapBytes / 3).coerceIn(48L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    private val pageLocks = Array(16) { Mutex() }
    private val cache = object : LruCache<Int, PageImage>(cacheBytes) {
        override fun sizeOf(key: Int, value: PageImage): Int = value.bitmap.byteCount
    }

    suspend fun loadGroup(visible: VisiblePages, targetWidth: Int): Map<Int, PageImage> = withContext(Dispatchers.IO) {
        val pages = listOfNotNull(visible.first, visible.second)
        pages.associateWith { page -> load(page, targetWidth) }
    }

    suspend fun prefetch(visible: VisiblePages, targetWidth: Int) = withContext(Dispatchers.IO) {
        listOfNotNull(visible.first, visible.second).forEach { page ->
            try {
                load(page, targetWidth)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed prefetch is retried and reported by the actual page request.
            }
        }
    }

    private suspend fun load(page: Int, targetWidth: Int): PageImage {
        val width = targetWidth.coerceIn(400, maxRenderWidth)
        cache.get(page)?.takeIf { !it.bitmap.isRecycled && it.bitmap.width >= width }?.let { return it }
        val requestContext = coroutineContext
        return pageLocks[page and 15].withLock {
            cache.get(page)?.takeIf { !it.bitmap.isRecycled && it.bitmap.width >= width }?.let { return@withLock it }
            requestContext.ensureActive()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(page).use { source ->
                        val rawHeight = width.toFloat() * source.height / source.width
                        val height = rawHeight.toInt().coerceIn(1, 3000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            requestContext.ensureActive()
                            PageImage(page, bitmap, source.width, source.height).also { cache.put(page, it) }
                        } catch (error: Exception) {
                            bitmap.recycle()
                            throw error
                        }
                    }
                }
            }
        }
    }

    fun clear() { cache.evictAll() }
}
