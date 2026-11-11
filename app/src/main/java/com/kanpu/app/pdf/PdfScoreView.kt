package com.kanpu.app.pdf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.kanpu.app.data.AnnotationEntity
import com.kanpu.app.data.PaperMode
import com.kanpu.app.domain.FingeringSize
import com.kanpu.app.domain.VisiblePages
import kotlin.math.abs

/** PDF and fingering share one page transform, so annotations survive rotation and zoom. */
class PdfScoreView(context: Context) : View(context) {
    var visiblePages: VisiblePages? = null
        private set
    var images: Map<Int, PageImage> = emptyMap()
        private set
    var annotations: List<AnnotationEntity> = emptyList()
        private set
    var paperMode: PaperMode = PaperMode.ORIGINAL
        set(value) { if (field != value) { field = value; invalidate() } }
    var doubleLayout: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var editing: Boolean = false
    var canPlaceMarker: Boolean = false
    var tapTurn: Boolean = true
    var swipeTurn: Boolean = true
    var selectedMarkerId: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var onTurn: (Int) -> Unit = {}
    var onCenterTap: () -> Unit = {}
    var onMarkerTap: (Int, Float, Float) -> Unit = { _, _, _ -> }
    var onMarkerSelected: (String) -> Unit = {}
    var onMarkerMoved: (AnnotationEntity, Float, Float) -> Unit = { _, _, _ -> }
    var onZoomOrPan: () -> Unit = {}
    var onTurnAnimationFinished: () -> Unit = {}

    private data class PageSlot(val index: Int, val rect: RectF)
    private data class PageSnapshot(
        val visible: VisiblePages,
        val images: Map<Int, PageImage>,
        val annotations: List<AnnotationEntity>,
        val doubleLayout: Boolean,
        val zoom: Float,
        val panX: Float,
        val panY: Float
    )
    private var slots: List<PageSlot> = emptyList()
    private var outgoing: PageSnapshot? = null
    private var turnAnimator: ValueAnimator? = null
    private var turnProgress = 1f
    private var turnDirection = 1
    private var transitionId = 0L
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var multiTouch = false
    private var moved = false
    private var touchedMarker: AnnotationEntity? = null
    private var dragPreview: AnnotationEntity? = null
    private val touchSlop = 8f * resources.displayMetrics.density
    private val swipeDistance = 48f * resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 118, 210)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val transform = Matrix()
    private val inverse = Matrix()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            if (next != zoom) {
                zoom = next
                if (zoom == 1f) { panX = 0f; panY = 0f }
                onZoomOrPan()
                invalidate()
            }
            return true
        }
    })

    fun setPageData(visible: VisiblePages, bitmaps: Map<Int, PageImage>, marks: List<AnnotationEntity>) {
        if (visiblePages == visible && images === bitmaps && annotations === marks) return
        val changed = visiblePages != visible
        val previous = if (changed) visiblePages?.let {
            PageSnapshot(it, images, annotations, doubleLayout, zoom, panX, panY)
        } else null
        if (changed) {
            transitionId++
            turnAnimator?.cancel()
            turnAnimator = null
            outgoing = null
        }
        visiblePages = visible
        images = bitmaps
        annotations = marks
        if (changed) resetZoom()
        if (changed) {
            val id = transitionId
            val shouldAnimate = previous != null && previous.images.isNotEmpty() &&
                previous.visible.first != visible.first &&
                !previous.visible.contains(visible.first) &&
                (visible.second == null || !previous.visible.contains(visible.second)) &&
                width > 0 && height > 0 && isAttachedToWindow
            if (shouldAnimate) {
                outgoing = previous
                turnDirection = if (visible.first > previous!!.visible.first) 1 else -1
                turnProgress = 0f
                turnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 220L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        turnProgress = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (transitionId == id) {
                                outgoing = null
                                turnAnimator = null
                                turnProgress = 1f
                                invalidate()
                                onTurnAnimationFinished()
                            }
                        }
                    })
                    start()
                }
            } else {
                turnProgress = 1f
                post { if (transitionId == id) onTurnAnimationFinished() }
            }
        }
        invalidate()
    }

    fun setAnnotations(marks: List<AnnotationEntity>) {
        annotations = marks
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        dragPreview = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (paperMode == PaperMode.INVERTED) Color.BLACK else Color.WHITE)
        outgoing?.let { old ->
            drawPages(canvas, old, -turnDirection * width * turnProgress, false)
        }
        val shown = visiblePages ?: return
        drawPages(canvas, PageSnapshot(shown, images, annotations, doubleLayout, zoom, panX, panY),
            if (outgoing == null) 0f else turnDirection * width * (1f - turnProgress), true)
    }

    private fun drawPages(canvas: Canvas, snapshot: PageSnapshot, offsetX: Float, current: Boolean) {
        val pageSlots = computeSlots(snapshot.visible, snapshot.images, snapshot.doubleLayout)
        val matrix = Matrix().apply {
            setScale(snapshot.zoom, snapshot.zoom, width / 2f, height / 2f)
            postTranslate(snapshot.panX, snapshot.panY)
        }
        if (current) {
            slots = pageSlots
            transform.set(matrix)
            transform.invert(inverse)
        }
        pagePaint.colorFilter = colorFilter(paperMode)
        canvas.save()
        canvas.translate(offsetX, 0f)
        canvas.concat(matrix)
        for (slot in pageSlots) {
            val bitmap = snapshot.images[slot.index]?.bitmap ?: continue
            if (!bitmap.isRecycled) canvas.drawBitmap(bitmap, null, slot.rect, pagePaint)
            snapshot.annotations.filter { it.pageIndex == slot.index }.forEach { mark ->
                val shown = if (current) dragPreview?.takeIf { it.id == mark.id } ?: mark else mark
                val x = slot.rect.left + shown.u * slot.rect.width()
                val y = slot.rect.top + shown.v * slot.rect.height()
                textPaint.textSize = (slot.rect.height() * FingeringSize.displayRatio(shown.fontSizeRatio)).coerceAtLeast(6f)
                textPaint.color = if (paperMode == PaperMode.INVERTED) {
                    val c = shown.color
                    Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
                } else shown.color
                val digit = shown.finger.toString()
                val digitWidth = textPaint.measureText(digit)
                canvas.drawText(digit, x, y, textPaint)
                val handLabel = when (shown.hand) { "LEFT" -> "左"; "RIGHT" -> "右"; else -> null }
                if (handLabel != null) {
                    handPaint.color = textPaint.color
                    handPaint.textSize = textPaint.textSize * .45f
                    canvas.drawText(handLabel, x + digitWidth / 2f, y - textPaint.textSize * .85f, handPaint)
                }
                if (current && shown.id == selectedMarkerId) {
                    val underlineY = y + textPaint.textSize * .17f
                    canvas.drawLine(x, underlineY, x + digitWidth, underlineY, selectPaint)
                }
            }
        }
        canvas.restore()
    }

    private fun computeSlots(pages: VisiblePages, pageImages: Map<Int, PageImage>, double: Boolean): List<PageSlot> {
        val indexes = listOfNotNull(pages.first, pages.second)
        val gap = if (double) 6f * resources.displayMetrics.density else 0f
        val slotWidth = if (double) (width - gap) / 2f else width.toFloat()
        return indexes.mapIndexedNotNull { ordinal, index ->
            if (pageImages[index] == null) return@mapIndexedNotNull null
            val sectionLeft = if (double) ordinal * (slotWidth + gap) else 0f
            // Keep every note visible while occupying the full reader surface.
            PageSlot(index, RectF(sectionLeft, 0f, sectionLeft + slotWidth, height.toFloat()))
        }
    }

    private fun colorFilter(mode: PaperMode): ColorMatrixColorFilter? {
        val values = when (mode) {
            PaperMode.ORIGINAL -> return null
            PaperMode.INVERTED -> floatArrayOf(
                -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)
        }
        return ColorMatrixColorFilter(ColorMatrix(values))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (turnAnimator?.isRunning == true) return true
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                multiTouch = false; moved = false; dragPreview = null
                touchedMarker = if (editing) hitMarker(event.x, event.y) else null
                touchedMarker?.let { onMarkerSelected(it.id) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; touchedMarker = null; dragPreview = null }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) multiTouch = true
                if (!multiTouch) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
                    if (moved && editing && touchedMarker != null) {
                        pointOnPage(event.x, event.y)?.takeIf { it.first == touchedMarker?.pageIndex }?.let { (_, u, v) ->
                            dragPreview = touchedMarker?.copy(u = u, v = v)
                            invalidate()
                        }
                    } else if (moved && zoom > 1f) {
                        panX += dx; panY += dy
                        onZoomOrPan()
                        invalidate()
                    }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!multiTouch) {
                    val dragged = dragPreview
                    if (dragged != null && touchedMarker != null) onMarkerMoved(touchedMarker!!, dragged.u, dragged.v)
                    else if (editing && touchedMarker == null && !moved) {
                        if (canPlaceMarker) {
                            pointOnPage(event.x, event.y)?.let { (page, u, v) -> onMarkerTap(page, u, v) }
                        } else if (event.x in width * .3f..width * .7f) {
                            onCenterTap()
                        }
                    } else if (!editing) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (moved && zoom == 1f && swipeTurn && abs(dx) >= swipeDistance && abs(dx) >= 1.5f * abs(dy)) {
                            onTurn(if (dx < 0) 1 else -1)
                        } else if (!moved) {
                            when {
                                tapTurn && event.x < width * .3f -> onTurn(-1)
                                tapTurn && event.x > width * .7f -> onTurn(1)
                                else -> onCenterTap()
                            }
                        }
                    }
                }
                touchedMarker = null; dragPreview = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { touchedMarker = null; dragPreview = null; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        transitionId++
        turnAnimator?.cancel()
        turnAnimator = null
        outgoing = null
        super.onDetachedFromWindow()
    }

    private fun pointOnPage(x: Float, y: Float): Triple<Int, Float, Float>? {
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val slot = slots.firstOrNull { it.rect.contains(point[0], point[1]) } ?: return null
        return Triple(slot.index,
            ((point[0] - slot.rect.left) / slot.rect.width()).coerceIn(0f, 1f),
            ((point[1] - slot.rect.top) / slot.rect.height()).coerceIn(0f, 1f))
    }

    private fun hitMarker(x: Float, y: Float): AnnotationEntity? {
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val radius = 28f * resources.displayMetrics.density / zoom
        return annotations.asReversed().firstOrNull { mark ->
            val slot = slots.firstOrNull { it.index == mark.pageIndex } ?: return@firstOrNull false
            val mx = slot.rect.left + mark.u * slot.rect.width()
            val my = slot.rect.top + mark.v * slot.rect.height()
            abs(point[0] - mx) < radius && abs(point[1] - my) < radius
        }
    }
}
