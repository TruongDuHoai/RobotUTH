package com.robotuth.v3.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.PageAgent
import com.ainirobot.agent.action.Actions
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.link.RobotLinkService
import com.robotuth.v3.stt.audio.OrionHomeCompat
import com.robotuth.v3.vision.FocusFollowController

/**
 * Entry activity.
 *
 * On AiniRobot Orion (OS-R-SG04): must run as a normal Activity **with**
 * AgentOS [PageAgent] / [AppAgent] (see [com.robotuth.v3.RobotUTHApplication]).
 * Overlay hacks and finishAndRemoveTask made Xiaobao re-enter default module
 * and still pip'd. Official samples (agent.sample) stay full-screen Activity
 * and only coordinate via AgentCore — no SYSTEM_ALERT_WINDOW.
 *
 * Phones / non-Orion: same UI without AgentCore calls.
 */
class MainActivityNew : AppCompatActivity() {

    private var deps: AppDependencies? = null
    private var pageAgent: PageAgent? = null
    private var focusFollow: FocusFollowController? = null
    private var isOrion: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOrion = OrionHomeCompat.isLikelyOrion(this)
        // No-ops by itself on phones/emulators; see FocusFollowController.
        focusFollow = FocusFollowController(this).also {
            Log.i(TAG, "focus follow available=${it.isSupported}")
        }

        if (isOrion) {
            try {
                // Bind this page into AgentOS so LiveKit does not treat us as a random thief
                pageAgent = PageAgent(this).also { agent ->
                    agent.blockAllActions()
                    agent.registerAction(Actions.SAY)
                    agent.setObjective(
                        "RobotUTH đang mở. Người dùng dùng giao diện cảm ứng; " +
                            "không tự ý điều hướng robot."
                    )
                }
                Log.i(TAG, "PageAgent ready")
            } catch (e: Exception) {
                Log.e(TAG, "PageAgent failed (AgentOS missing?): ${e.message}", e)
            }
        }

        startNormalUi()
    }

    override fun onStart() {
        super.onStart()
        // Ask Orion to keep the person in front of it in view (no-op off-robot).
        focusFollow?.start()
        // Remote-control Phase 1 (register/heartbeat/reconnect) — default OFF in AppConfig.
        if (AppConfig.ROBOT_LINK_ENABLED) {
            RobotLinkService.start(this)
        }
        if (!isOrion) return
        try {
            // Match agent.sample: stop residual TTS, hide system voice bar
            AgentCore.stopTTS()
            AgentCore.clearContext()
            AgentCore.isEnableVoiceBar = false
            AgentCore.isDisablePlan = true
            AgentCore.uploadInterfaceInfo(
                "RobotUTH: điểm danh khuôn mặt, chat tri thức, hội thoại giọng nói, điều hướng."
            )
            Log.i(TAG, "AgentCore page start — voiceBar=off plan=off")
        } catch (e: Exception) {
            Log.w(TAG, "AgentCore onStart: ${e.message}")
        }
    }

    override fun onStop() {
        // Focus follow holds the chassis — always release it when we leave foreground.
        focusFollow?.stop()
        deps?.robotFaceViewModel?.onLeaveScreen()
        super.onStop()
    }

    private fun startNormalUi() {
        if (isTablet()) enableImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val created = AppDependencyFactory.create(this, this)
        deps = created

        setContent {
            RobotUTHApp(
                homeViewModel = created.homeViewModel,
                faceViewModel = created.faceViewModel,
                chatViewModel = created.chatViewModel,
                robotFaceViewModel = created.robotFaceViewModel,
                cameraManager = created.cameraManager,
                faceDetector = created.faceDetector,
                onExitApp = {
                    try {
                        if (isOrion) {
                            AgentCore.stopTTS()
                            AgentCore.clearContext()
                        }
                    } catch (_: Exception) {
                    }
                    finish()
                }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isTablet() && deps != null) enableImmersiveMode()
    }

    override fun onDestroy() {
        deps?.release()
        deps = null
        focusFollow?.release()
        focusFollow = null
        pageAgent = null
        super.onDestroy()
    }

    private fun isTablet(): Boolean = resources.configuration.screenWidthDp >= 600

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    companion object {
        private const val TAG = "MainActivityNew"
    }
}
