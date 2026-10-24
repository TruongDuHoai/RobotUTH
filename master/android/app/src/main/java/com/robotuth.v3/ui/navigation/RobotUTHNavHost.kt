package com.robotuth.v3.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.robotuth.v3.data.camera.CameraManager
import com.robotuth.v3.data.camera.CameraManagerImpl
import com.robotuth.v3.data.mlkit.FaceDetector
import com.robotuth.v3.data.mlkit.MLKitFaceDetector
import com.robotuth.v3.ui.chat.ChatScreen
import com.robotuth.v3.ui.face.FaceScreen
import com.robotuth.v3.ui.face.FaceViewModel
import com.robotuth.v3.ui.home.HomeScreen
import com.robotuth.v3.ui.home.HomeViewModel
import com.robotuth.v3.ui.robot.RobotFaceScreen
import com.robotuth.v3.ui.robot.RobotFaceViewModel
import com.robotuth.v3.ui.navigation.NavigationScreen
import com.robotuth.v3.ui.chat.ChatViewModel

@Composable
fun RobotUTHNavHost(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    faceViewModel: FaceViewModel,
    chatViewModel: ChatViewModel,
    robotFaceViewModel: RobotFaceViewModel,
    cameraManager: CameraManager,
    faceDetector: FaceDetector,
    onExitApp: (() -> Unit)? = null,
    startDestination: String = Screen.Home.route,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                onNavigateToConversation = { navController.navigate(Screen.Conversation.route) },
                onNavigateToNavigation = { navController.navigate(Screen.Navigation.route) },
                onExitApp = onExitApp
            )
        }

        composable(Screen.Chat.route) {
            ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.Attendance.route) {
            FaceScreen(
                viewModel = faceViewModel,
                cameraManager = cameraManager,
                faceDetector = faceDetector,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Conversation.route) {
            RobotFaceScreen(viewModel = robotFaceViewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.Navigation.route) {
            NavigationScreen(onBack = { navController.popBackStack() })
        }
    }
}

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Chat : Screen("chat")
    data object Attendance : Screen("attendance")
    data object Conversation : Screen("conversation")
    data object Navigation : Screen("navigation")
}
