package com.robotuth.v3.stt.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AiniRobot Orion (Xiaobao / home) compatibility.
 *
 * Opening a normal third-party full-screen Activity backgrounds
 * [com.ainirobot.home]. AgentService LiveKit then glitches ("pip pip") and can
 * leave the speaker dead until home is top again.
 *
 * Rules that actually matter on device:
 *  - Prefer TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE (keep home window focus).
 *  - Do **not** restart agentservice SpeechService on every open (causes reconnect pip).
 *  - Bootstrap Activity must be translucent / finishAndRemoveTask ASAP.
 */
object OrionHomeCompat {

    private const val TAG = "OrionHomeCompat"
    private const val HOME_PKG = "com.ainirobot.home"
    private const val AGENT_PKG = "com.ainirobot.agentservice"
    private const val CORE_PKG = "com.ainirobot.coreservice"

    fun isLikelyOrion(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(HOME_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * RobotApi/PersonApi (visual ability) live behind AiniRobot CoreService.
     * Absent on phones and emulators → callers must skip their vendor calls entirely.
     */
    fun hasCoreService(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(CORE_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Soft HOME only — does **not** poke agentservice.
     * Prefer letting the system resume home after [finishAndRemoveTask].
     */
    fun bringHomeToFront(context: Context, restartAgentSpeech: Boolean = false) {
        if (!isLikelyOrion(context)) return
        try {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            context.startActivity(home)
            Log.i(TAG, "bringHomeToFront HOME restartAgent=$restartAgentSpeech")
        } catch (e: Exception) {
            Log.w(TAG, "bringHomeToFront failed: ${e.message}")
            try {
                val launch = Intent().setComponent(
                    ComponentName(HOME_PKG, "$HOME_PKG.LaunchActivity")
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                context.startActivity(launch)
            } catch (e2: Exception) {
                Log.w(TAG, "LaunchActivity failed: ${e2.message}")
            }
        }
        if (restartAgentSpeech) {
            startAgentSpeech(context)
        }
    }

    /**
     * Only when speaker is already dead after we leave — not on app open.
     * Restarting LiveKit while healthy causes the "pip pip" reconnect noise.
     */
    fun startAgentSpeech(context: Context) {
        for (action in listOf(
            "com.ainirobot.speech.voicedata",
            "com.ainirobot.home",
            "com.ainirobot.moduleapp"
        )) {
            try {
                val i = Intent(action).setPackage(AGENT_PKG)
                context.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "startAgentSpeech $action: ${e.message}")
            }
        }
    }
}
