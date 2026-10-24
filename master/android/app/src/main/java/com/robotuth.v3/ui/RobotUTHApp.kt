package com.robotuth.v3.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.robotuth.v3.data.camera.CameraManager
import com.robotuth.v3.data.mlkit.FaceDetector
import com.robotuth.v3.ui.chat.ChatViewModel
import com.robotuth.v3.ui.face.FaceViewModel
import com.robotuth.v3.ui.home.HomeViewModel
import com.robotuth.v3.ui.navigation.RobotUTHNavHost
import com.robotuth.v3.ui.robot.RobotFaceViewModel
import com.robotuth.v3.ui.theme.RobotUTHTheme

@Composable
fun RobotUTHApp(
    homeViewModel: HomeViewModel,
    faceViewModel: FaceViewModel,
    chatViewModel: ChatViewModel,
    robotFaceViewModel: RobotFaceViewModel,
    cameraManager: CameraManager,
    faceDetector: FaceDetector,
    onExitApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    RobotUTHTheme {
        val navController = rememberNavController()

        RobotUTHNavHost(
            navController = navController,
            homeViewModel = homeViewModel,
            faceViewModel = faceViewModel,
            chatViewModel = chatViewModel,
            robotFaceViewModel = robotFaceViewModel,
            cameraManager = cameraManager,
            faceDetector = faceDetector,
            onExitApp = onExitApp,
            modifier = modifier
        )
    }
}
