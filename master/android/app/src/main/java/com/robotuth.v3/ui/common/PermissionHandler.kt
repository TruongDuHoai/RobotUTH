package com.robotuth.v3.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun PermissionHandler(
    permission: String,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            onPermissionGranted()
        } else {
            onPermissionDenied?.invoke()
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            launcher.launch(permission)
        } else {
            onPermissionGranted()
        }
    }
}

@Composable
fun AudioPermissionHandler(
    permission: String,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: (() -> Unit)? = null
) = PermissionHandler(permission, onPermissionGranted, onPermissionDenied)

@Composable
fun CameraPermissionHandler(
    permission: String,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: (() -> Unit)? = null
) = PermissionHandler(permission, onPermissionGranted, onPermissionDenied)
