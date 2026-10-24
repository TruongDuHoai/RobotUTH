package com.robotuth.v3.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.ui.common.WebViewScreen

@Composable
fun NavigationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    WebViewScreen(
        url = AppConfig.VR_CAMPUS_URL,
        onBack = onBack,
        modifier = modifier
    )
}