package com.robotuth.v3.ui.face

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

private val BgLight = Color(0xFFE6F4F3)
private val Primary = Color(0xFF008689)
private val TextDark = Color(0xFF0F172A)
private val TextMuted = Color(0xFF64748B)
private val CardBg = Color(0xFFF8FAFC)

@Composable
fun FaceContent(
    state: FaceUiState,
    @Suppress("UNUSED_PARAMETER") onEvent: (FaceUiEvent) -> Unit,
    cameraPreview: @Composable (modifier: Modifier) -> Unit,
    @Suppress("UNUSED_PARAMETER") previewViewSize: android.util.Pair<Int, Int>? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(formatTime()) }
    var currentDate by remember { mutableStateOf(formatDate()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = formatTime()
            currentDate = formatDate()
            delay(1000L)
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // ── Left: Camera 2/3 ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
        ) {
            cameraPreview(Modifier.fillMaxSize())

            // Face bounds overlay
            FaceBoundsOverlay(
                faceBounds = state.detectedFaceBounds,
                imageWidth = state.detectedImageWidth,
                imageHeight = state.detectedImageHeight,
                rotationDegrees = state.rotationDegrees,
                isFrontCamera = state.cameraFacing == CameraFacing.FRONT,
                modifier = Modifier.fillMaxSize()
            )

            // Back button overlay
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A)
                )
            }

            // Greeting overlay on success
            androidx.compose.animation.AnimatedVisibility(
                visible = state.attendanceState == AttendanceState.SUCCESS,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 },
                exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 2 },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Xin ch\u00E0o!",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.recognizedName ?: "",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "\u0110i\u1EC3m danh th\u00E0nh c\u00F4ng",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // ── Right: Info panel 1/3 ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(BgLight)
                .statusBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top — date/time
            Column {
                Text(
                    text = currentTime,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentDate,
                    fontSize = 14.sp,
                    color = TextMuted
                )
            }

            // Bottom — status
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val (statusText, statusColor) = when (state.attendanceState) {
                    AttendanceState.SEARCHING -> "Đưa mặt vào khung" to TextMuted
                    AttendanceState.POSITIONING -> "Giữ nguyên..." to Primary
                    AttendanceState.TOO_CLOSE -> "Lùi lại" to Color(0xFFF44336)
                    AttendanceState.TOO_FAR -> "Đến gần hơn" to Color(0xFFF44336)
                    AttendanceState.RECOGNIZING -> "Đang nhận diện..." to Primary
                    AttendanceState.SUCCESS -> "Thành công" to Color(0xFF4CAF50)
                    AttendanceState.RETRY -> "Thử lại" to Color(0xFFFF9800)
                    AttendanceState.MULTI_FACE -> "Chỉ 1 người" to Color(0xFFF44336)
                }

                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    textAlign = TextAlign.Center
                )

                if (state.attendanceState == AttendanceState.RECOGNIZING) {
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

private fun formatTime(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun formatDate(): String =
    SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(Date())

@Composable
private fun FaceBoundsOverlay(
    faceBounds: FaceBounds?,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    isFrontCamera: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokeWidth = with(density) { 3.dp.toPx() }
    val cornerLen = with(density) { 20.dp.toPx() }

    Canvas(modifier = modifier) {
        val b = faceBounds ?: return@Canvas
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val mlW = if (isRotated) imageHeight.toFloat() else imageWidth.toFloat()
        val mlH = if (isRotated) imageWidth.toFloat() else imageHeight.toFloat()
        if (mlW <= 0f || mlH <= 0f) return@Canvas

        val scale = maxOf(size.width / mlW, size.height / mlH)
        val offsetX = (size.width - mlW * scale) / 2f
        val offsetY = (size.height - mlH * scale) / 2f

        var left = b.left * scale + offsetX
        var top = b.top * scale + offsetY
        var right = b.right * scale + offsetX
        var bottom = b.bottom * scale + offsetY

        if (isFrontCamera) {
            val tmpL = left
            left = size.width - right
            right = size.width - tmpL
        }

        val color = Color(0xFF008689)

        // Top-left corner
        drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)
        drawLine(color, Offset(left, top), Offset(left, top + cornerLen), strokeWidth)
        // Top-right corner
        drawLine(color, Offset(right, top), Offset(right - cornerLen, top), strokeWidth)
        drawLine(color, Offset(right, top), Offset(right, top + cornerLen), strokeWidth)
        // Bottom-left corner
        drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeWidth)
        // Bottom-right corner
        drawLine(color, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeWidth)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeWidth)
    }
}
