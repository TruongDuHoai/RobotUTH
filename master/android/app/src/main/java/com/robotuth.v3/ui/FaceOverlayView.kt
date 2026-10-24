package com.robotuth.v3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faceLeft = 0f
    private var faceTop = 0f
    private var faceRight = 0f
    private var faceBottom = 0f
    private var hasFace = false

    private val boxPaint = Paint().apply {
        color = Color.rgb(0, 134, 137)  // RGB(0, 134, 137)
        style = Paint.Style.STROKE
        strokeWidth = 8f  // 3dp equivalent
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.rgb(0, 134, 137)
        textSize = 40f
        isAntiAlias = true
    }

    fun setFaceBounds(left: Float, top: Float, right: Float, bottom: Float) {
        faceLeft = left
        faceTop = top
        faceRight = right
        faceBottom = bottom
        hasFace = true
        invalidate()
    }

    fun clearFace() {
        hasFace = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (hasFace) {
            // Draw bounding box rectangle
            canvas.drawRect(faceLeft, faceTop, faceRight, faceBottom, boxPaint)

            // Draw label "Face" at top-left corner
            canvas.drawText("Face", faceLeft, faceTop - 10, labelPaint)
        }
    }
}
