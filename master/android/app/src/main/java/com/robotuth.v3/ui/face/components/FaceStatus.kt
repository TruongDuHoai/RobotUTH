package com.robotuth.v3.ui.face.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robotuth.v3.ui.face.AttendanceState

@Composable
fun FaceStatus(
    message: String,
    attendanceState: AttendanceState,
    modifier: Modifier = Modifier
) {
    val cardColor = when (attendanceState) {
        AttendanceState.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.15f)
        AttendanceState.TOO_CLOSE,
        AttendanceState.TOO_FAR,
        AttendanceState.MULTI_FACE -> Color(0xFFF44336).copy(alpha = 0.15f)
        AttendanceState.RECOGNIZING -> Color(0xFF2196F3).copy(alpha = 0.15f)
        AttendanceState.POSITIONING -> Color(0xFFFF9800).copy(alpha = 0.15f)
        else -> Color.White.copy(alpha = 0.1f)
    }

    val textColor = when (attendanceState) {
        AttendanceState.SUCCESS -> Color(0xFF4CAF50)
        AttendanceState.TOO_CLOSE,
        AttendanceState.TOO_FAR,
        AttendanceState.MULTI_FACE -> Color(0xFFF44336)
        AttendanceState.RECOGNIZING -> Color(0xFF2196F3)
        AttendanceState.POSITIONING -> Color(0xFFFF9800)
        else -> Color.White
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}
