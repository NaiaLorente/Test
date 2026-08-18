package com.charchat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Lets the user choose exactly what part of a photo becomes a character's avatar: pinch to zoom,
 * drag to reposition, always keeping the square crop frame fully covered so there's never empty
 * space in the result. [croppedBitmap] renders exactly what's currently visible inside the frame.
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private var minScale = 1f
    private var maxScale = 1f

    private val frameRect = RectF()
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isDragging = false

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        if (width > 0 && height > 0) fitImageToFrame()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = minOf(w, h) * 0.8f
        frameRect.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        bitmap?.let { fitImageToFrame() }
    }

    /** Centers and scales the bitmap so it fully covers the crop frame, like centerCrop. */
    private fun fitImageToFrame() {
        val bmp = bitmap ?: return
        val scale = maxOf(frameRect.width() / bmp.width, frameRect.height() / bmp.height)
        minScale = scale
        maxScale = scale * 4f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(
            frameRect.centerX() - bmp.width * scale / 2f,
            frameRect.centerY() - bmp.height * scale / 2f
        )
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (currentScale() * factor).coerceIn(minScale, maxScale)
        val actualFactor = newScale / currentScale()
        imageMatrix.postScale(actualFactor, actualFactor, focusX, focusY)
        clampTranslation()
        invalidate()
    }

    /** Keeps the bitmap covering the crop frame at all times - no panning/zooming past its edges. */
    private fun clampTranslation() {
        val bmp = bitmap ?: return
        val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(bounds)

        var dx = 0f
        var dy = 0f
        if (bounds.left > frameRect.left) dx = frameRect.left - bounds.left
        else if (bounds.right < frameRect.right) dx = frameRect.right - bounds.right
        if (bounds.top > frameRect.top) dy = frameRect.top - bounds.top
        else if (bounds.bottom < frameRect.bottom) dy = frameRect.bottom - bounds.bottom
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        imageMatrix.postTranslate(x - lastTouchX, y - lastTouchY)
                        clampTranslation()
                        lastTouchX = x
                        lastTouchY = y
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        // Dim everything outside the crop frame using an even-odd (rect minus circle) path.
        val dimPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addOval(frameRect, Path.Direction.CW)
        }
        canvas.drawPath(dimPath, overlayPaint)
        canvas.drawOval(frameRect, framePaint)
    }

    /** Renders exactly what's currently visible inside the crop frame, at [outputSize]x[outputSize]. */
    fun croppedBitmap(outputSize: Int = 512): Bitmap? {
        val bmp = bitmap ?: return null
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val outputMatrix = Matrix(imageMatrix)
        outputMatrix.postTranslate(-frameRect.left, -frameRect.top)
        val scale = outputSize / frameRect.width()
        outputMatrix.postScale(scale, scale)
        canvas.drawBitmap(bmp, outputMatrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
