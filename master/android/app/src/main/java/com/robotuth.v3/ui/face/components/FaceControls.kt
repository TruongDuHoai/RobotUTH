package com.robotuth.v3.ui.face.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun FaceControls(
    isAutoDetectEnabled: Boolean,
    canRecognize: Boolean,
    isProcessing: Boolean,
    faceServerUrl: String,
    onAutoDetectToggle: (Boolean) -> Unit,
    onFaceServerUrlChanged: (String) -> Unit,
    onFaceServerConnect: () -> Unit,
    onRecognizeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Auto Detect Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Auto Detect",
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = isAutoDetectEnabled,
                onCheckedChange = onAutoDetectToggle,
                enabled = !isProcessing
            )
        }

        // Face Server URL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = faceServerUrl,
                onValueChange = onFaceServerUrlChanged,
                label = { Text("Face server URL") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                enabled = !isProcessing
            )

            OutlinedButton(
                onClick = onFaceServerConnect,
                enabled = !isProcessing
            ) {
                Text("Connect")
            }
        }

        // Action Button
        Button(
            onClick = onRecognizeClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = canRecognize && !isProcessing
        ) {
            Text(
                text = "Điểm danh",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
