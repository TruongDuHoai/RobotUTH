package com.robotuth.v3.stt.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Audio coordination for AiniRobot Orion (OS-R-SG04).
 *
 * Xiaobao / agentservice keeps a permanent LiveKit [AudioTrack] on
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION]. Stealing focus with MEDIA/ASSISTANT
 * or leaving MODE_IN_COMMUNICATION causes "pip pip" then dead speaker until
 * Xiaobao restarts the voice path.
 *
 * Rules:
 * - Mic capture: do **not** request audio focus (avoid focus war with LiveKit).
 * - TTS: play on USAGE_VOICE_COMMUNICATION (same path as Xiaobao).
 * - On leave: abandon focus, MODE_NORMAL, nudge agentservice SpeechService back.
 */
class RobotAudioSession(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus: Boolean = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audioFocus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hasFocus = false
            AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
        }
    }

    /** Raise playback streams if settings left them near mute (Orion often has MUSIC=3). */
    fun ensureAudibleVolumes() {
        try {
            bumpStream(AudioManager.STREAM_MUSIC, minIndex = 8)
            bumpStream(AudioManager.STREAM_VOICE_CALL, minIndex = 3)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // STREAM_TTS may not exist on all builds; ignore failures
                @Suppress("DEPRECATION")
                bumpStream(AudioManager.STREAM_MUSIC, minIndex = 8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureAudibleVolumes: ${e.message}")
        }
    }

    private fun bumpStream(stream: Int, minIndex: Int) {
        val max = audioManager.getStreamMaxVolume(stream)
        val cur = audioManager.getStreamVolume(stream)
        val target = minOf(max, maxOf(cur, minIndex))
        if (cur < target) {
            audioManager.setStreamVolume(stream, target, 0)
            Log.i(TAG, "stream=$stream volume $cur → $target (max=$max)")
        }
    }

    /**
     * Request focus for **TTS only**, attributes match agentservice LiveKit
     * so Android routes to the same communication path.
     */
    fun requestPlaybackFocus(): Boolean {
        return try {
            ensureAudibleVolumes()
            val attrs = voiceCommAttrs()
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "requestPlaybackFocus result=$result granted=$hasFocus")
            // Even if not granted, still try to play — Orion often returns FAILED but mixes
            true
        } catch (e: Exception) {
            Log.w(TAG, "requestPlaybackFocus failed: ${e.message}")
            true
        }
    }

    /** @deprecated Mic must not steal focus on Orion — no-op kept for call-site safety. */
    fun requestFocus(forPlayback: Boolean): Boolean {
        return if (forPlayback) requestPlaybackFocus() else true
    }

    fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonFocus failed: ${e.message}")
        } finally {
            focusRequest = null
            hasFocus = false
        }
    }

    fun resetRouting() {
        try {
            @Suppress("DEPRECATION")
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "setMode MODE_NORMAL")
            }
        } catch (e: Exception) {
            Log.w(TAG, "resetRouting mode: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            if (audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Nudge agentservice to re-bind voice path after our app leaves
     * (same intents Xiaobao / home use for SpeechService).
     */
    fun restoreAgentVoicePath() {
        val actions = listOf(
            "com.ainirobot.speech.voicedata",
            "com.ainirobot.home",
            "com.ainirobot.moduleapp"
        )
        for (action in actions) {
            try {
                val intent = Intent(action).setPackage("com.ainirobot.agentservice")
                appContext.startService(intent)
                Log.i(TAG, "startService action=$action")
            } catch (e: Exception) {
                Log.w(TAG, "restoreAgentVoicePath $action: ${e.message}")
            }
        }
    }

    /**
     * Full teardown. Prefer [abandonFocus]+[resetRouting] during normal UI leave;
     * only call [restoreAgentVoicePath] when the whole app is finishing (see OrionHomeCompat).
     */
    fun release(restoreAgent: Boolean = false) {
        abandonFocus()
        resetRouting()
        if (restoreAgent) {
            restoreAgentVoicePath()
        }
        Log.i(TAG, "release complete restoreAgent=$restoreAgent")
    }

    companion object {
        private const val TAG = "RobotAudioSession"

        fun voiceCommAttrs(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        fun mediaAttrs(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
