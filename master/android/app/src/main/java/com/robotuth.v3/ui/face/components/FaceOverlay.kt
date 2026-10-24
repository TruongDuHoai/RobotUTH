package com.robotuth.v3.ui.face.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.robotuth.v3.ui.face.AttendanceState

@Composable
fun FaceOvalGuide(
    attendanceState: AttendanceState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = when (attendanceState) {
                AttendanceState.SEARCHING -> 1200
                AttendanceState.RECOGNIZING -> 400
                AttendanceState.SUCCESS -> 600
                AttendanceState.RETRY -> 500
                else -> 800
            }),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val strokeColor = when (attendanceState) {
        AttendanceState.SEARCHING -> Color.Gray.copy(alpha = pulseAlpha)
        AttendanceState.POSITIONING -> Color(0xFFFF9800)
        AttendanceState.TOO_CLOSE -> Color(0xFFF44336)
        AttendanceState.TOO_FAR -> Color(0xFFF44336)
        AttendanceState.RECOGNIZING -> Color(0xFF2196F3).copy(alpha = pulseAlpha)
        AttendanceState.SUCCESS -> Color(0xFF4CAF50).copy(alpha = pulseAlpha)
        AttendanceState.RETRY -> Color(0xFFFF9800).copy(alpha = pulseAlpha)
        AttendanceState.MULTI_FACE -> Color(0xFFF44336)
    }

    val strokeWidth = with(density) { (if (attendanceState == AttendanceState.SUCCESS) 5.dp else 3.dp).toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val ovalWidth = size.width * 0.65f
        val ovalHeight = size.height * 0.55f
        val centerX = size.width / 2f
        val centerY = size.height * 0.42f

        drawOval(
            color = strokeColor,
            topLeft = Offset(centerX - ovalWidth / 2f, centerY - ovalHeight / 2f),
            size = Size(ovalWidth, ovalHeight),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        if (attendanceState == AttendanceState.SEARCHING || attendanceState == AttendanceState.POSITIONING) {
            val crossSize = 12f
            drawLine(
                color = strokeColor.copy(alpha = 0.5f),
                start = Offset(centerX - crossSize, centerY),
                end = Offset(centerX + crossSize, centerY),
                strokeWidth = 1.5f
            )
            drawLine(
                color = strokeColor.copy(alpha = 0.5f),
                start = Offset(centerX, centerY - crossSize),
                end = Offset(centerX, centerY + crossSize),
                strokeWidth = 1.5f
            )
        }
    }
}
