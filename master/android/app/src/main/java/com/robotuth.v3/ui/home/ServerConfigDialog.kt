package com.robotuth.v3.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ServerConfigDialog(
    step: ConfigStep,
    serverUrl: String,
    passwordError: String?,
    onPasswordSubmit: (String) -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onServerUrlSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember(step) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (step) {
                    ConfigStep.Password -> "Cấu hình hệ thống"
                    ConfigStep.ServerUrl -> "Server URL"
                }
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (step) {
                    ConfigStep.Password -> {
                        Text(
                            text = "Nhập mật khẩu cấu hình",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Mật khẩu") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { onPasswordSubmit(password) }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            isError = passwordError != null
                        )
                        if (passwordError != null) {
                            Text(
                                text = passwordError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    ConfigStep.ServerUrl -> {
                        Text(
                            text = "Nhập địa chỉ server backend (ví dụ: http://172.16.1.15:10010/)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = onServerUrlChanged,
                            label = { Text("Server URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { onServerUrlSave() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (step) {
                        ConfigStep.Password -> onPasswordSubmit(password)
                        ConfigStep.ServerUrl -> onServerUrlSave()
                    }
                }
            ) {
                Text(if (step == ConfigStep.Password) "Tiếp" else "Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}