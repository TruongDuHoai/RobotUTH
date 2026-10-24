package com.robotuth.v3.ui

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.data.camera.CameraManagerImpl
import com.robotuth.v3.data.mlkit.MLKitFaceDetector
import com.robotuth.v3.data.repository.SettingsRepositoryImpl
import com.robotuth.v3.domain.repository.SettingsRepository
import com.robotuth.v3.domain.usecase.DetectFaceUseCaseImpl
import com.robotuth.v3.domain.usecase.EnrollFaceUseCaseImpl
import com.robotuth.v3.domain.usecase.RecognizeFaceUseCaseImpl
import com.robotuth.v3.repository.FaceRepository
import com.robotuth.v3.stt.ParakeetSttService
import com.robotuth.v3.ui.chat.ChatViewModel
import com.robotuth.v3.ui.face.FaceViewModel
import com.robotuth.v3.ui.home.HomeViewModel
import com.robotuth.v3.ui.robot.RobotFaceViewModel
import com.robotuth.v3.utils.UrlHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

data class AppDependencies(
    val homeViewModel: HomeViewModel,
    val faceViewModel: FaceViewModel,
    val chatViewModel: ChatViewModel,
    val robotFaceViewModel: RobotFaceViewModel,
    val cameraManager: CameraManagerImpl,
    val faceDetector: MLKitFaceDetector,
    val robotSttService: ParakeetSttService,
    val parakeetService: ParakeetSttService
) {
    fun release() {
        try {
            robotFaceViewModel.onLeaveScreen()
        } catch (_: Exception) {
        }
        try {
            robotSttService.release()
        } catch (_: Exception) {
        }
        try {
            parakeetService.release()
        } catch (_: Exception) {
        }
        try {
            cameraManager.stopCamera()
        } catch (_: Exception) {
        }
    }
}

object AppDependencyFactory {

    fun create(context: Context, lifecycleOwner: LifecycleOwner): AppDependencies {
        val appContext = context.applicationContext
        val settings: SettingsRepository = SettingsRepositoryImpl(appContext)
        val cameraManager = CameraManagerImpl(appContext, lifecycleOwner)
        val faceDetector = MLKitFaceDetector()
        val faceRepo = FaceRepository()

        val parakeetService = ParakeetSttService(appContext)
        val robotSttService = ParakeetSttService(appContext)
        applySavedServerUrl(settings, parakeetService, robotSttService)

        val homeViewModel = HomeViewModel(
            settingsRepository = settings,
            onServerUrlApplied = { url -> applyServerUrl(url, parakeetService, robotSttService) }
        )
        val faceViewModel = FaceViewModel(
            detectFaceUseCase = DetectFaceUseCaseImpl(faceRepo),
            enrollFaceUseCase = EnrollFaceUseCaseImpl(faceRepo),
            recognizeFaceUseCase = RecognizeFaceUseCaseImpl(faceRepo),
            settingsRepository = settings,
            faceRepository = faceRepo
        )
        val chatViewModel = ChatViewModel(settingsRepository = settings)
        val robotFaceViewModel = RobotFaceViewModel(
            settingsRepository = settings,
            ctcService = robotSttService,
            appContext = appContext
        )

        return AppDependencies(
            homeViewModel = homeViewModel,
            faceViewModel = faceViewModel,
            chatViewModel = chatViewModel,
            robotFaceViewModel = robotFaceViewModel,
            cameraManager = cameraManager,
            faceDetector = faceDetector,
            robotSttService = robotSttService,
            parakeetService = parakeetService
        )
    }

    private fun applySavedServerUrl(
        settings: SettingsRepository,
        vararg sttServices: ParakeetSttService
    ) {
        runBlocking {
            val savedUrl = settings.settings.first().serverUrl
            applyServerUrl(savedUrl, *sttServices)
        }
    }

    fun applyServerUrl(url: String, vararg sttServices: ParakeetSttService) {
        val normalized = UrlHelper.ensureTrailingSlash(url)
        RetrofitClient.setBaseUrl(normalized)
        sttServices.forEach { it.setServerUrl(normalized) }
    }
}
