package com.robotuth.v3.ui.robot

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp

// Cheerful Teal (#018486) + White Color Palette
private val BodyColor = Color(0xFF018486)
private val BodyDark = Color(0xFF015A5C)
private val BodyLight = Color(0xFF03B8BB)
private val FaceColor = Color.White
private val FaceBorder = Color(0xFFDCEFEF)
private val EyeWhite = Color.White
private val EyePupil = Color(0xFF1E293B)
private val EyeIris = Color(0xFF018486)
private val EyeHighlight = Color.White
private val MouthColor = Color(0xFF018486)
private val CheekBlush = Color(0x3303B8BB)
private val AntennaGlow = Color(0xFF03B8BB)
private val EarColor = Color(0xFF015A5C)

@Composable
fun RobotFaceCanvas(
    emotion: RobotEmotion,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "robot")

    val blink by infinite.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 3500; 1f at 0; 1f at 2800; 0.05f at 2950; 1f at 3100
        }), label = "blink"
    )

    val bob by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "bob"
    )

    val antPulse by infinite.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "antPulse"
    )

    val lookX by infinite.animateFloat(
        initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 5000; 0f at 0; 0f at 2000; 0.8f at 3000; -0.8f at 4000; 0f at 5000
        }), label = "look"
    )

    val bounceScale by infinite.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 1200; 1f at 0; 1.04f at 300; 1f at 600; 1.02f at 900; 1f at 1200
        }), label = "bounce"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val unit = minOf(w, h)
        val happyScale = if (emotion == RobotEmotion.Happy) bounceScale else 1f

        val headR = unit * 0.30f
        val bobOffset = bob * unit * 0.008f
        val headCenter = Offset(cx, h * 0.38f - bobOffset)

        // BODY / SHOULDERS
        val bodyTop = headCenter.y + headR * 0.75f
        val shoulderW = headR * 1.6f
        val shoulderH = headR * 0.7f
        val neckW = headR * 0.45f

        drawRoundRect(
            color = BodyColor,
            topLeft = Offset(cx - neckW / 2, headCenter.y + headR * 0.65f),
            size = Size(neckW, headR * 0.35f),
            cornerRadius = CornerRadius(headR * 0.1f)
        )

        val shoulderPath = Path().apply {
            moveTo(cx - shoulderW * 0.5f, bodyTop + shoulderH)
            quadraticBezierTo(cx - shoulderW * 0.55f, bodyTop, cx - neckW / 2 - headR * 0.05f, bodyTop - headR * 0.05f)
            lineTo(cx + neckW / 2 + headR * 0.05f, bodyTop - headR * 0.05f)
            quadraticBezierTo(cx + shoulderW * 0.55f, bodyTop, cx + shoulderW * 0.5f, bodyTop + shoulderH)
            close()
        }
        drawPath(shoulderPath, BodyColor)

        // Body accent stripe
        drawLine(
            Color.White.copy(alpha = 0.3f),
            Offset(cx, bodyTop),
            Offset(cx, bodyTop + shoulderH),
            strokeWidth = headR * 0.06f,
            cap = StrokeCap.Round
        )

        // EARS
        val earR = headR * 0.14f
        val earY = headCenter.y - headR * 0.05f
        drawCircle(EarColor, earR, Offset(headCenter.x - headR - earR * 0.4f, earY))
        drawCircle(Color.White.copy(alpha = 0.4f), earR * 0.5f, Offset(headCenter.x - headR - earR * 0.4f, earY))
        drawCircle(EarColor, earR, Offset(headCenter.x + headR + earR * 0.4f, earY))
        drawCircle(Color.White.copy(alpha = 0.4f), earR * 0.5f, Offset(headCenter.x + headR + earR * 0.4f, earY))

        // ANTENNA
        val antBase = Offset(headCenter.x, headCenter.y - headR)
        val antTop = Offset(headCenter.x + headR * 0.04f, headCenter.y - headR - headR * 0.38f)
        drawLine(BodyDark, antBase, antTop, strokeWidth = unit * 0.012f, cap = StrokeCap.Round)

        val antBallR = headR * 0.1f * (if (emotion == RobotEmotion.Listening || emotion == RobotEmotion.Speaking) antPulse else 1f)
        val antBallColor = when (emotion) {
            RobotEmotion.Listening -> Color(0xFF4CAF50)
            RobotEmotion.Speaking -> Color(0xFFFF9800)
            RobotEmotion.Happy -> Color(0xFFFF4081)
            RobotEmotion.Error -> Color(0xFFF44336)
            else -> AntennaGlow
        }
        drawCircle(antBallColor.copy(alpha = 0.15f), antBallR * 1.8f, antTop)
        drawCircle(antBallColor, antBallR, antTop)
        drawCircle(Color.White.copy(alpha = 0.7f), antBallR * 0.35f, Offset(antTop.x - antBallR * 0.2f, antTop.y - antBallR * 0.2f))

        // HEAD
        drawCircle(Color.Black.copy(alpha = 0.03f), headR * 1.02f, Offset(headCenter.x + 2f, headCenter.y + 3f))
        
        withTransform({
            scale(happyScale, happyScale, headCenter)
        }) {
            drawCircle(FaceColor, headR, headCenter)
            drawCircle(FaceBorder, headR, headCenter, style = Stroke(width = unit * 0.012f))

            // EYES
            val eyeSpacing = headR * 0.42f
            val eyeY = headCenter.y - headR * 0.06f
            val leftEye = Offset(headCenter.x - eyeSpacing, eyeY)
            val rightEye = Offset(headCenter.x + eyeSpacing, eyeY)
            val eyeR = headR * 0.22f

            when (emotion) {
                RobotEmotion.Happy, RobotEmotion.Idle -> {
                    drawHappyArc(leftEye, eyeR, EyePupil)
                    drawHappyArc(rightEye, eyeR, EyePupil)
                }
                RobotEmotion.Listening -> {
                    drawSparklyEye(leftEye, eyeR * 1.15f, antPulse, lookX)
                    drawSparklyEye(rightEye, eyeR * 1.15f, antPulse, lookX)
                }
                RobotEmotion.Thinking -> {
                    drawCuteEye(leftEye, eyeR, blink, 0.7f)
                    drawCuteEye(rightEye, eyeR * 0.85f, blink, 0.7f)
                }
                RobotEmotion.Speaking -> {
                    drawCuteEye(leftEye, eyeR * 1.05f, antPulse * 0.3f + 0.7f, lookX * 0.3f)
                    drawCuteEye(rightEye, eyeR * 1.05f, antPulse * 0.3f + 0.7f, lookX * 0.3f)
                }
                RobotEmotion.Surprised -> {
                    drawSparklyEye(leftEye, eyeR * 1.3f, 1f, 0f)
                    drawSparklyEye(rightEye, eyeR * 1.3f, 1f, 0f)
                }
                RobotEmotion.Sad -> {
                    drawSadEye(leftEye, eyeR, lookX * 0.2f)
                    drawSadEye(rightEye, eyeR, lookX * 0.2f)
                }
                RobotEmotion.Error -> {
                    drawXEye(leftEye, eyeR * 0.8f)
                    drawXEye(rightEye, eyeR * 0.8f)
                }
            }

            // CHEEK BLUSH
            if (emotion == RobotEmotion.Happy || emotion == RobotEmotion.Idle) {
                val blushR = headR * 0.13f
                drawOval(CheekBlush, topLeft = Offset(leftEye.x - headR * 0.25f - blushR, eyeY + headR * 0.18f), size = Size(blushR * 2.2f, blushR * 1.2f))
                drawOval(CheekBlush, topLeft = Offset(rightEye.x + headR * 0.25f - blushR, eyeY + headR * 0.18f), size = Size(blushR * 2.2f, blushR * 1.2f))
            }

            // EYEBROWS
            val browY = eyeY - eyeR - headR * 0.12f
            val browLen = headR * 0.18f
            when (emotion) {
                RobotEmotion.Surprised -> {
                    drawCuteBrow(Offset(leftEye.x, browY - headR * 0.06f), browLen, 0f)
                    drawCuteBrow(Offset(rightEye.x, browY - headR * 0.06f), browLen, 0f)
                }
                RobotEmotion.Sad -> {
                    drawCuteBrow(Offset(leftEye.x, browY), browLen, 12f)
                    drawCuteBrow(Offset(rightEye.x, browY), browLen, -12f)
                }
                RobotEmotion.Error -> {
                    drawCuteBrow(Offset(leftEye.x, browY), browLen, 18f)
                    drawCuteBrow(Offset(rightEye.x, browY), browLen, -18f)
                }
                RobotEmotion.Thinking -> {
                    drawCuteBrow(Offset(leftEye.x, browY - headR * 0.03f), browLen, -8f)
                    drawCuteBrow(Offset(rightEye.x, browY), browLen, 3f)
                }
                else -> {
                    drawCuteBrow(Offset(leftEye.x, browY), browLen, -2f)
                    drawCuteBrow(Offset(rightEye.x, browY), browLen, 2f)
                }
            }

            // MOUTH
            val mouthY = headCenter.y + headR * 0.3f
            val mouthW = headR * 0.3f

            when (emotion) {
                RobotEmotion.Happy, RobotEmotion.Idle -> {
                    drawBigSmile(Offset(headCenter.x, mouthY), mouthW, headR * 0.18f)
                }
                RobotEmotion.Speaking -> {
                    val open = (bob * 0.5f + 0.5f) * headR * 0.1f + headR * 0.04f
                    drawCuteSpeakingMouth(Offset(headCenter.x, mouthY), mouthW * 0.9f, open)
                }
                RobotEmotion.Surprised -> {
                    drawCuteO(Offset(headCenter.x, mouthY + headR * 0.02f), headR * 0.09f)
                }
                RobotEmotion.Sad -> {
                    drawSadMouth(Offset(headCenter.x, mouthY + headR * 0.06f), mouthW * 0.7f, headR * 0.07f)
                }
                RobotEmotion.Error -> {
                    drawWavyMouth(Offset(headCenter.x, mouthY), mouthW)
                }
                RobotEmotion.Thinking -> {
                    drawThinkingPout(Offset(headCenter.x, mouthY), mouthW * 0.4f)
                }
                RobotEmotion.Listening -> {
                    drawCuteO(Offset(headCenter.x, mouthY), headR * 0.06f)
                }
            }
        }
    }
}

// EYE HELPERS

private fun DrawScope.drawHappyArc(center: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x - r, center.y + r * 0.1f)
        quadraticBezierTo(center.x, center.y - r * 1.2f, center.x + r, center.y + r * 0.1f)
    }
    drawPath(path, color, style = Stroke(width = r * 0.45f, cap = StrokeCap.Round))
}

private fun DrawScope.drawCuteEye(center: Offset, r: Float, blink: Float, lookDir: Float) {
    val h = r * 2f * blink.coerceAtLeast(0.15f)
    drawOval(EyeWhite, topLeft = Offset(center.x - r, center.y - h / 2f), size = Size(r * 2, h))
    if (blink > 0.3f) {
        val irisR = r * 0.55f
        val px = center.x + lookDir * r * 0.25f
        drawCircle(EyeIris, irisR, Offset(px, center.y))
        drawCircle(EyePupil, r * 0.3f, Offset(px, center.y))
        drawCircle(EyeHighlight, r * 0.18f, Offset(px - r * 0.12f, center.y - r * 0.15f))
        drawCircle(EyeHighlight.copy(alpha = 0.7f), r * 0.08f, Offset(px + r * 0.12f, center.y + r * 0.08f))
    }
}

private fun DrawScope.drawSparklyEye(center: Offset, r: Float, pulse: Float, lookDir: Float) {
    drawCircle(AntennaGlow.copy(alpha = 0.06f * pulse), r * 1.5f, center)
    drawCircle(EyeWhite, r, center)
    val irisR = r * 0.65f
    val px = center.x + lookDir * r * 0.2f
    val irisGrad = Brush.radialGradient(listOf(EyeIris, EyePupil), center = Offset(px, center.y), radius = irisR)
    drawCircle(irisGrad, irisR, Offset(px, center.y))
    val starR = r * 0.22f
    drawCircle(EyeHighlight, starR, Offset(px - r * 0.18f, center.y - r * 0.2f))
    drawCircle(EyeHighlight.copy(alpha = 0.6f), starR * 0.5f, Offset(px + r * 0.15f, center.y + r * 0.1f))
    drawCircle(Color.White.copy(alpha = 0.8f), r * 0.06f, Offset(px - r * 0.3f, center.y + r * 0.25f))
    drawCircle(Color.White.copy(alpha = 0.5f), r * 0.04f, Offset(px + r * 0.25f, center.y - r * 0.3f))
}

private fun DrawScope.drawSadEye(center: Offset, r: Float, lookDir: Float) {
    drawOval(EyeWhite, topLeft = Offset(center.x - r, center.y - r * 0.7f), size = Size(r * 2, r * 1.4f))
    val pupilR = r * 0.3f
    drawCircle(EyePupil, pupilR, Offset(center.x + lookDir * r * 0.2f, center.y + r * 0.1f))
    drawCircle(EyeHighlight, pupilR * 0.35f, Offset(center.x - r * 0.08f, center.y - r * 0.05f))
}

private fun DrawScope.drawXEye(center: Offset, r: Float) {
    val s = r * 0.65f
    val w = r * 0.28f
    drawLine(Color(0xFFF44336), Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), w, cap = StrokeCap.Round)
    drawLine(Color(0xFFF44336), Offset(center.x + s, center.y - s), Offset(center.x - s, center.y + s), w, cap = StrokeCap.Round)
}

private fun DrawScope.drawCuteBrow(center: Offset, len: Float, angleDeg: Float) {
    rotate(angleDeg, pivot = center) {
        drawLine(EyePupil.copy(alpha = 0.3f), Offset(center.x - len, center.y), Offset(center.x + len, center.y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

// MOUTH HELPERS

private fun DrawScope.drawBigSmile(center: Offset, w: Float, depth: Float) {
    val leftPath = Path().apply {
        moveTo(center.x - w, center.y - depth * 0.3f)
        quadraticBezierTo(center.x - w * 0.3f, center.y + depth * 1.8f, center.x, center.y + depth * 0.2f)
    }
    val rightPath = Path().apply {
        moveTo(center.x, center.y + depth * 0.2f)
        quadraticBezierTo(center.x + w * 0.3f, center.y + depth * 1.8f, center.x + w, center.y - depth * 0.3f)
    }
    val sw = 3.5.dp.toPx()
    drawPath(leftPath, MouthColor, style = Stroke(width = sw, cap = StrokeCap.Round))
    drawPath(rightPath, MouthColor, style = Stroke(width = sw, cap = StrokeCap.Round))
}

private fun DrawScope.drawCuteSpeakingMouth(center: Offset, w: Float, openH: Float) {
    drawRoundRect(MouthColor, topLeft = Offset(center.x - w, center.y - openH), size = Size(w * 2, openH * 2f), cornerRadius = CornerRadius(w * 0.5f, w * 0.4f))
    drawRoundRect(Color(0xFFB71C1C).copy(alpha = 0.5f), topLeft = Offset(center.x - w * 0.65f, center.y - openH * 0.5f), size = Size(w * 1.3f, openH * 1f), cornerRadius = CornerRadius(w * 0.35f))
}

private fun DrawScope.drawCuteO(center: Offset, r: Float) {
    drawCircle(MouthColor, r, center)
    drawCircle(Color(0xFFB71C1C).copy(alpha = 0.35f), r * 0.5f, center)
}

private fun DrawScope.drawSadMouth(center: Offset, w: Float, depth: Float) {
    val path = Path().apply {
        moveTo(center.x - w, center.y)
        quadraticBezierTo(center.x, center.y - depth * 1.5f, center.x + w, center.y)
    }
    drawPath(path, MouthColor.copy(alpha = 0.6f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawWavyMouth(center: Offset, w: Float) {
    val path = Path().apply {
        moveTo(center.x - w, center.y)
        cubicTo(center.x - w * 0.5f, center.y + 8.dp.toPx(), center.x - w * 0.1f, center.y - 8.dp.toPx(), center.x, center.y)
        cubicTo(center.x + w * 0.1f, center.y + 8.dp.toPx(), center.x + w * 0.5f, center.y - 8.dp.toPx(), center.x + w, center.y)
    }
    drawPath(path, Color(0xFFF44336), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawThinkingPout(center: Offset, r: Float) {
    val offset = Offset(center.x + r * 0.8f, center.y)
    drawCircle(MouthColor.copy(alpha = 0.65f), r, offset)
    drawCircle(FaceColor.copy(alpha = 0.4f), r * 0.4f, offset)
}