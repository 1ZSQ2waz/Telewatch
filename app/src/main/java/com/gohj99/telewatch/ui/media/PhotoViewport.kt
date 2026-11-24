package com.gohj99.telewatch.ui.media

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Draws the same fitted image through one matrix for both touch and multitouch. */
internal class PhotoViewport(context: Context) : ImageView(context) {
    var onSave: () -> Unit = {}
    var onTransformGesture: (Boolean) -> Unit = {}
    private val transform = Matrix()
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var pinchX = 0f
    private var pinchY = 0f
    private var pinchSpan = 0f
    private var firstPinchPointer = MotionEvent.INVALID_POINTER_ID
    private var secondPinchPointer = MotionEvent.INVALID_POINTER_ID
    private var pinching = false
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var multitouch = false
    private var transforming = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        scaleType = ScaleType.MATRIX
        isFocusable = false
    }

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !dragging && !multitouch) {
                changeZoom(if (zoom > 1f) 1f else 2.5f, e.x, e.y, e.x, e.y)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (drawable != null && !dragging && !multitouch) onSave()
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multitouch = false
                dragging = false
                trackPointer(event, 0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multitouch) {
                    // The second finger cancels pending double taps and long presses.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    taps.onTouchEvent(cancel)
                    cancel.recycle()
                }
                multitouch = true
                if (event.pointerCount == 2) startPinch(event)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (dragging || hypot(x - lastX, y - lastY) >= touchSlop) {
                        dragging = true
                        setTransforming(true)
                        offsetX += x - lastX
                        offsetY += y - lastY
                        renderTransform()
                        lastX = x
                        lastY = y
                    }
                }
            } else {
                updatePinch(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    trackPointer(event, if (event.actionIndex == 0) 1 else 0)
                    dragging = true
                } else {
                    val lifted = event.getPointerId(event.actionIndex)
                    if (lifted == firstPinchPointer || lifted == secondPinchPointer) {
                        startPinch(event, skip = event.actionIndex)
                    }
                }
            }
        }
        if (!multitouch) taps.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activePointer = MotionEvent.INVALID_POINTER_ID
            parent?.requestDisallowInterceptTouchEvent(false)
            setTransforming(false)
        }
        return true
    }

    // Android's phone-sized minimum pinch span can be wider than a comfortable gesture on a watch.
    private fun startPinch(event: MotionEvent, skip: Int = -1) {
        val first = if (skip == 0) 1 else 0
        val second = if (skip <= 1 && skip >= 0) 2 else 1
        firstPinchPointer = event.getPointerId(first)
        secondPinchPointer = event.getPointerId(second)
        val dx = event.getX(second) - event.getX(first)
        val dy = event.getY(second) - event.getY(first)
        pinchSpan = hypot(dx, dy).coerceAtLeast(touchSlop.toFloat())
        pinchX = event.getX(first) + dx / 2
        pinchY = event.getY(first) + dy / 2
        pinching = false
    }

    private fun updatePinch(event: MotionEvent) {
        val first = event.findPointerIndex(firstPinchPointer)
        val second = event.findPointerIndex(secondPinchPointer)
        if (first < 0 || second < 0) return
        val dx = event.getX(second) - event.getX(first)
        val dy = event.getY(second) - event.getY(first)
        val span = hypot(dx, dy).coerceAtLeast(touchSlop.toFloat())
        val centerX = event.getX(first) + dx / 2
        val centerY = event.getY(first) + dy / 2
        if (!pinching && abs(span - pinchSpan) < touchSlop / 2f &&
            hypot(centerX - pinchX, centerY - pinchY) < touchSlop
        ) return
        pinching = true
        setTransforming(true)
        changeZoom(zoom * (span / pinchSpan), pinchX, pinchY, centerX, centerY)
        pinchSpan = span
        pinchX = centerX
        pinchY = centerY
    }

    private fun trackPointer(event: MotionEvent, index: Int) {
        activePointer = event.getPointerId(index)
        lastX = event.getX(index)
        lastY = event.getY(index)
    }

    private fun setTransforming(active: Boolean) {
        if (transforming == active) return
        transforming = active
        onTransformGesture(active)
    }

    private fun changeZoom(scale: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (drawable == null || !scale.isFinite()) return
        val next = scale.coerceIn(1f, MAX_ZOOM)
        val factor = next / zoom
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        offsetX = toX - centerX + (centerX + offsetX - fromX) * factor
        offsetY = toY - centerY + (centerY + offsetY - fromY) * factor
        zoom = next
        renderTransform()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        if (this.drawable === drawable) return
        super.setImageDrawable(drawable)
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        renderTransform()
    }

    /** Higher-resolution versions of this photo keep the user's zoom and pan. */
    fun replaceImage(image: Drawable) {
        if (drawable == null) {
            setImageDrawable(image)
        } else {
            super.setImageDrawable(image)
            renderTransform()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderTransform()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val density = resources.displayMetrics.density
            val edge = (24 * density).roundToInt().coerceAtMost(w / 2)
            val protectedHeight = (200 * density).roundToInt().coerceAtMost(h)
            val top = (h - protectedHeight) / 2
            systemGestureExclusionRects = listOf(
                Rect(0, top, edge, top + protectedHeight),
                Rect(w - edge, top, w, top + protectedHeight)
            )
        }
    }

    private fun renderTransform() {
        val image = drawable ?: return
        val imageWidth = image.intrinsicWidth
        val imageHeight = image.intrinsicHeight
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return
        val drawnScale = zoom * minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val drawnWidth = imageWidth * drawnScale
        val drawnHeight = imageHeight * drawnScale
        val limitX = ((drawnWidth - width) / 2).coerceAtLeast(0f)
        val limitY = ((drawnHeight - height) / 2).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
        transform.setScale(drawnScale, drawnScale)
        transform.postTranslate((width - drawnWidth) / 2 + offsetX, (height - drawnHeight) / 2 + offsetY)
        imageMatrix = transform
    }

    companion object {
        private const val MAX_ZOOM = 5f
    }
}
