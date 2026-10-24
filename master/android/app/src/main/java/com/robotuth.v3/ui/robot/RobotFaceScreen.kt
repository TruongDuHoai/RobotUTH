package com.robotuth.v3.ui.robot

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon

// Light theme: Teal (#018486) + White
private val LightBg = Color(0xFFF5FAFA)
private val PrimaryTeal = Color(0xFF018486)
private val PrimaryLight = Color(0xFF03B8BB)
private val PrimaryDark = Color(0xFF015A5C)
private val AccentGreen = Color(0xFF4CAF50)
private val AccentOrange = Color(0xFFFF9800)
private val AccentRed = Color(0xFFF44336)
private val TextDark = Color(0xFF0F172A)
private val TextMuted = Color(0xFF64748B)

@Composable
fun RobotFaceScreen(
    viewModel: RobotFaceViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onMicPermissionGranted()
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    // Ask for mic as soon as conversation screen opens (do not auto-start recording)
    LaunchedEffect(Unit) {
        viewModel.onEnterScreen()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Leaving screen / process death: free mic + restore speaker for Xiaobao
    DisposableEffect(Unit) {
        onDispose {
            viewModel.onLeaveScreen()
        }
    }

    fun onMicClick() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        viewModel.onMicTap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBg)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Back button top-left
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A)
                )
            }

            // Robot face — fills most of screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Soft gradient glow drawn programmatically behind face
                val glowAlpha by animateFloatAsState(
                    targetValue = when (uiState.emotion) {
                        RobotEmotion.Listening -> 0.15f
                        RobotEmotion.Speaking -> 0.12f
                        RobotEmotion.Happy -> 0.15f
                        RobotEmotion.Error -> 0.1f
                        else -> 0.04f
                    },
                    animationSpec = tween(600),
                    label = "glow"
                )
                val glowColor = when (uiState.emotion) {
                    RobotEmotion.Listening -> AccentGreen
                    RobotEmotion.Speaking -> AccentOrange
                    RobotEmotion.Happy -> Color(0xFFFF4081)
                    RobotEmotion.Error -> AccentRed
                    else -> PrimaryTeal
                }

                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension / 2f
                                ),
                                radius = size.minDimension / 2f
                            )
                        }
                )

                RobotFaceCanvas(
                    emotion = uiState.emotion,
                    modifier = Modifier.fillMaxSize(0.85f)
                )
            }

            // Status text
            AnimatedContent(
                targetState = uiState.statusText,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "status"
            ) { status ->
                Text(
                    text = status,
                    color = statusColor(uiState.emotion),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }

            // Latest message preview
            if (uiState.messages.isNotEmpty()) {
                val lastMsg = uiState.messages.last()
                Text(
                    text = if (lastMsg.isUser) "\uD83D\uDC64 ${lastMsg.text}" else "\uD83E\uDD16 ${lastMsg.text}",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp)
                )
            }

            // Mic button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp, top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MicButton(
                    isRecording = uiState.pipelineState == VoicePipelineState.Recording,
                    isProcessing = uiState.pipelineState == VoicePipelineState.Thinking ||
                            uiState.pipelineState == VoicePipelineState.ProcessingStt,
                    onClick = { onMicClick() }
                )
            }
        }
    }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "pulse"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> AccentRed
            isProcessing -> AccentOrange
            else -> PrimaryTeal
        },
        animationSpec = tween(300),
        label = "btnColor"
    )

    val scale = if (isRecording) pulseScale else 1f

    Box(contentAlignment = Alignment.Center) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .background(AccentRed.copy(alpha = 0.12f), CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .shadow(6.dp, CircleShape, ambientColor = buttonColor.copy(alpha = 0.25f))
                .background(buttonColor, CircleShape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isRecording -> "\u23F9"
                    isProcessing -> "\u23F3"
                    else -> "\uD83C\uDFA4"
                },
                fontSize = 28.sp
            )
        }
    }
}

private fun statusColor(emotion: RobotEmotion): Color {
    return when (emotion) {
        RobotEmotion.Listening -> AccentGreen
        RobotEmotion.Thinking -> AccentOrange
        RobotEmotion.Speaking -> PrimaryLight
        RobotEmotion.Happy -> Color(0xFFFFD740)
        RobotEmotion.Error -> AccentRed
        else -> TextMuted
    }
}
