package com.handdraw.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class CanvasBackground { CAMERA, WHITE }

/**
 * Renders committed strokes, the in-progress stroke, the live hand
 * skeleton, and the cursor — and owns the whole drawing engine
 * (undo/redo, partial eraser, pinch-to-grab), ported 1:1 in spirit
 * from the original Python `Drawing` class.
 */
class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---------------- 21-point hand connections (MediaPipe layout) ----------------
    private val handConnections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )

    // ---------------- state ----------------
    private val strokes = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<List<Stroke>>()
    private val redoStack = mutableListOf<List<Stroke>>()
    private var currentStroke: Stroke? = null

    var brush: String = "Pen"
        private set
    var drawColor: Int = Color.parseColor("#14141A")
        private set
    var sizePx: Float = Brushes.PRESETS["Pen"]!!.widthDp
        private set
    var eraserMode: Boolean = false
        private set
    var backgroundMode: CanvasBackground = CanvasBackground.CAMERA

    var showHand: Boolean = true
    private var handOverlay: List<PointF>? = null

    private var cursor: PointF? = null
    private var smoothedCursor: PointF? = null
    private val smoothing = 0.35f

    private var grabbing = false
    private val grabbedIndices = mutableListOf<Int>()
    private var lastGrabPoint: PointF? = null
    private var pinchFrames = 0
    private var releaseFrames = 0

    var onStatusChanged: ((String) -> Unit)? = null

    // ---------------- paints (reused across draws) ----------------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint().apply { color = Color.WHITE }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4187F5")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // ============================================================
    // Public configuration API (called from MainActivity)
    // ============================================================
    fun setBrush(name: String) {
        val preset = Brushes.PRESETS[name] ?: return
        brush = name
        sizePx = preset.widthDp
        eraserMode = false
        invalidate()
    }

    fun setColor(color: Int) {
        drawColor = color
        eraserMode = false
        invalidate()
    }

    fun toggleEraser() {
        eraserMode = !eraserMode
        invalidate()
    }

    fun changeSize(deltaDp: Float) {
        sizePx = max(1f, min(70f, sizePx + deltaDp))
        invalidate()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(strokes.map { it.deepCopy() })
        strokes.clear()
        strokes.addAll(undoStack.removeAt(undoStack.lastIndex))
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(strokes.map { it.deepCopy() })
        strokes.clear()
        strokes.addAll(redoStack.removeAt(redoStack.lastIndex))
        invalidate()
    }

    fun clear() {
        if (strokes.isEmpty()) return
        pushHistory()
        strokes.clear()
        invalidate()
    }

    fun setShowHand(show: Boolean) {
        showHand = show
        invalidate()
    }

    fun cycleBackground() {
        backgroundMode = if (backgroundMode == CanvasBackground.CAMERA)
            CanvasBackground.WHITE else CanvasBackground.CAMERA
        invalidate()
    }

    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        for (s in strokes) drawStroke(c, s)
        return bmp
    }

    // ============================================================
    // History
    // ============================================================
    private fun pushHistory() {
        undoStack.add(strokes.map { it.deepCopy() })
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
    }

    // ============================================================
    // Stroke lifecycle
    // ============================================================
    private fun startStroke(p: PointF) {
        currentStroke = Stroke(
            points = mutableListOf(PointF(p.x, p.y)),
            color = drawColor,
            widthPx = sizePx,
            alpha = Brushes.PRESETS[brush]?.alpha ?: 255,
            brush = brush
        )
    }

    private fun addPoint(p: PointF) {
        val stroke = currentStroke ?: return
        val pts = stroke.points
        if (pts.isEmpty()) { pts.add(p); return }
        val last = pts.last()
        val d = GeometryUtils.distance(last, p)
        if (d < 1f) return
        val steps = max(1, (d / 3f).toInt())
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            pts.add(PointF(last.x + (p.x - last.x) * t, last.y + (p.y - last.y) * t))
        }
    }

    private fun finishStroke() {
        val stroke = currentStroke ?: return
        if (stroke.points.isNotEmpty()) {
            pushHistory()
            strokes.add(stroke)
        }
        currentStroke = null
    }

    // ============================================================
    // Partial eraser — mirrors the original segment-splitting logic
    // ============================================================
    private fun erase(point: PointF) {
        val radius = max(12f, sizePx * 0.75f)
        val newStrokes = mutableListOf<Stroke>()
        var changed = false

        for (obj in strokes) {
            val pts = obj.points
            if (pts.size < 2) {
                if (pts.isNotEmpty() && GeometryUtils.distance(point, pts[0]) <= radius) {
                    changed = true
                } else {
                    newStrokes.add(obj)
                }
                continue
            }

            val segments = mutableListOf<MutableList<PointF>>()
            var current = mutableListOf<PointF>()

            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val hit = GeometryUtils.pointSegmentDistance(point, a, b) <= radius
                if (hit) {
                    changed = true
                    if (current.size >= 2) segments.add(current)
                    current = mutableListOf()
                } else {
                    if (current.isEmpty()) current.add(a)
                    current.add(b)
                }
            }
            if (current.size >= 2) segments.add(current)

            for (seg in segments) {
                newStrokes.add(obj.copy(points = seg))
            }
        }

        if (changed) {
            pushHistory()
            strokes.clear()
            strokes.addAll(newStrokes)
        }
    }

    // ============================================================
    // Grab / move (pinch anywhere on canvas to nudge a stroke)
    // ============================================================
    private fun objectCenter(obj: Stroke): PointF {
        val pts = obj.points
        if (pts.isEmpty()) return PointF(0f, 0f)
        var sx = 0f; var sy = 0f
        for (p in pts) { sx += p.x; sy += p.y }
        return PointF(sx / pts.size, sy / pts.size)
    }

    private fun findNearest(point: PointF): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for ((i, obj) in strokes.withIndex()) {
            val pts = obj.points
            if (pts.size == 1) {
                val d = GeometryUtils.distance(point, pts[0])
                if (d < bestDist) { bestDist = d; best = i }
            } else {
                for (j in 0 until pts.size - 1) {
                    val d = GeometryUtils.pointSegmentDistance(point, pts[j], pts[j + 1])
                    if (d < bestDist) { bestDist = d; best = i }
                }
            }
        }
        val limit = max(35f, sizePx * 3f)
        return if (best != null && bestDist <= limit) best else null
    }

    private fun startGrab(point: PointF) {
        val index = findNearest(point)
        if (index == null) { grabbedIndices.clear(); return }
        pushHistory()
        grabbedIndices.clear()
        grabbedIndices.add(index)
        val center = objectCenter(strokes[index])
        for ((i, obj) in strokes.withIndex()) {
            if (i == index) continue
            if (GeometryUtils.distance(center, objectCenter(obj)) < 100f) grabbedIndices.add(i)
        }
        lastGrabPoint = point
    }

    private fun moveGrab(point: PointF) {
        if (grabbedIndices.isEmpty()) return
        val last = lastGrabPoint
        if (last == null) { lastGrabPoint = point; return }
        val dx = point.x - last.x
        val dy = point.y - last.y
        for (index in grabbedIndices) {
            if (index >= strokes.size) continue
            val obj = strokes[index]
            for (i in obj.points.indices) {
                obj.points[i] = PointF(obj.points[i].x + dx, obj.points[i].y + dy)
            }
        }
        lastGrabPoint = point
    }

    private fun endGrab() {
        grabbing = false
        grabbedIndices.clear()
        lastGrabPoint = null
    }

    // ============================================================
    // Main per-frame update — called from the hand-landmark callback
    // with normalized [0..1] points already mirrored for the front
    // camera. Pass null when no hand is visible.
    // ============================================================
    fun processHandFrame(landmarksNorm: List<PointF>?) {
        if (landmarksNorm == null || landmarksNorm.size < 21) {
            finishStroke()
            endGrab()
            cursor = null
            smoothedCursor = null
            handOverlay = null
            onStatusChanged?.invoke("NO HAND")
            invalidate()
            return
        }

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        handOverlay = landmarksNorm.map { PointF(it.x * w, it.y * h) }

        val rawCursor = PointF(
            (landmarksNorm[8].x * w).coerceIn(0f, w),
            (landmarksNorm[8].y * h).coerceIn(0f, h)
        )

        val prev = smoothedCursor
        val newCursor = if (prev == null) rawCursor else PointF(
            prev.x + (rawCursor.x - prev.x) * smoothing,
            prev.y + (rawCursor.y - prev.y) * smoothing
        )
        smoothedCursor = newCursor
        cursor = newCursor

        // ---- pinch detection (normalized-space thumb/index distance) ----
        val pinchDist = GeometryUtils.distance(landmarksNorm[4], landmarksNorm[8])
        val pinchThreshold = if (!grabbing) 0.065f else 0.09f
        val pinching = pinchDist < pinchThreshold

        if (pinching) { pinchFrames++; releaseFrames = 0 } else { releaseFrames++; pinchFrames = 0 }

        if (!grabbing && pinchFrames >= 3) {
            finishStroke()
            grabbing = true
            startGrab(newCursor)
        }

        if (grabbing) {
            if (pinching) {
                moveGrab(newCursor)
                onStatusChanged?.invoke("GRABBING")
            } else if (releaseFrames >= 4) {
                endGrab()
            }
            invalidate()
            return
        }

        // ---- draw gesture: index finger extended, others curled ----
        val indexExtended = landmarksNorm[8].y < landmarksNorm[6].y
        val othersClosed = landmarksNorm[12].y > landmarksNorm[10].y &&
            landmarksNorm[16].y > landmarksNorm[14].y &&
            landmarksNorm[20].y > landmarksNorm[18].y
        val drawingGesture = indexExtended && othersClosed

        if (drawingGesture) {
            if (eraserMode) {
                finishStroke()
                erase(newCursor)
                onStatusChanged?.invoke("ERASING")
            } else {
                if (currentStroke == null) startStroke(newCursor) else addPoint(newCursor)
                onStatusChanged?.invoke("DRAWING")
            }
        } else {
            finishStroke()
            onStatusChanged?.invoke("READY")
        }

        invalidate()
    }

    // ============================================================
    // Rendering
    // ============================================================
    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val pts = s.points
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            dotPaint.color = s.color
            dotPaint.alpha = s.alpha
            canvas.drawCircle(pts[0].x, pts[0].y, s.widthPx / 2f, dotPaint)
            return
        }
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        strokePaint.color = s.color
        strokePaint.alpha = s.alpha
        strokePaint.strokeWidth = s.widthPx
        canvas.drawPath(path, strokePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (backgroundMode == CanvasBackground.WHITE) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        for (s in strokes) drawStroke(canvas, s)
        currentStroke?.let { drawStroke(canvas, it) }

        if (showHand) {
            handOverlay?.let { pts ->
                for ((a, b) in handConnections) {
                    if (a < pts.size && b < pts.size) {
                        canvas.drawLine(pts[a].x, pts[a].y, pts[b].x, pts[b].y, skeletonPaint)
                    }
                }
                for ((i, p) in pts.withIndex()) {
                    jointPaint.color = when (i) {
                        8 -> Color.parseColor("#F0BE32")
                        4 -> Color.parseColor("#E14650")
                        else -> Color.parseColor("#4187F5")
                    }
                    val r = if (i == 8) 9f else if (i == 4) 8f else 5f
                    canvas.drawCircle(p.x, p.y, r, jointPaint)
                }
            }
        }

        cursor?.let { c ->
            cursorPaint.color = when {
                grabbing -> Color.parseColor("#32BE6E")
                currentStroke != null -> Color.parseColor("#F0BE32")
                else -> Color.parseColor("#4187F5")
            }
            canvas.drawCircle(c.x, c.y, 15f, cursorPaint)
        }
    }
}
