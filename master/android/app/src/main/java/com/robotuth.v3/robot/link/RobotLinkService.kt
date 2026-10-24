package com.robotuth.v3.robot.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.robot.core.AppControlClaim
import com.robotuth.v3.robot.core.RobotCoreConnection
import com.robotuth.v3.robot.map.MapManager
import com.robotuth.v3.robot.motion.OrionMotion
import com.robotuth.v3.robot.motion.RobotMotion
import com.robotuth.v3.robot.motion.SimMotion
import com.robotuth.v3.ui.MainActivityNew

/**
 * Foreground service hosting the persistent robot-link WebSocket (remote-control spec:
 * the connection must not belong to an Activity lifecycle).
 *
 * Motion implementation is picked at start: [OrionMotion] when CoreService is present,
 * otherwise [SimMotion] so the link stays testable on phones/emulators. Everything is
 * gated on [AppConfig.ROBOT_LINK_ENABLED] — default OFF until on-robot validation.
 */
class RobotLinkService : Service() {

    private var linkClient: RobotLinkClient? = null
    private var coreConnection: RobotCoreConnection? = null
    private var appControlClaim: AppControlClaim? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AppConfig.ROBOT_LINK_ENABLED) {
            Log.i(TAG, "robot link disabled by AppConfig — not starting")
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        if (linkClient == null) {
            val motion: RobotMotion = OrionMotion(this).takeIf { it.isAvailable() } ?: SimMotion()
            if (motion is OrionMotion) {
                // Claim CoreService app control: without it SuspendClient denies every
                // chassis action with rc=-7 (whitelist race at CoreService boot leaves
                // this app permanently "suspended").
                appControlClaim = AppControlClaim(this).also { it.start() }
            }
            val maps: MapManager =
                com.robotuth.v3.robot.map.OrionMapManager(this)
                    .takeIf { it.isAvailable() }
                    ?: com.robotuth.v3.robot.map.SimMapManager()
            val core = RobotCoreConnection(this).also {
                coreConnection = it
                // Real readiness instead of the "unknown" placeholders: localization
                // comes from the motion impl, map/mapping from the map manager.
                it.localizationProvider = { motion.localizationState() }
                it.mappingProvider = { if (maps.isMapping()) "active" else "idle" }
                it.mapProvider = { maps.currentMapName() }
                it.connect()
            }
            Log.i(TAG, "robot link motion impl=${motion.javaClass.simpleName} " +
                "maps impl=${maps.javaClass.simpleName}")
            linkClient = RobotLinkClient(
                robotId = robotId(),
                wsBase = AppConfig.ROBOT_LINK_WS_URL,
                token = AppConfig.ROBOT_API_TOKEN,
                motion = motion,
                maps = maps,
                statusProvider = { readBattery() },
                readinessProvider = { core.snapshot() },
            ).also { client ->
                core.addListener(object : RobotCoreConnection.Listener {
                    override fun onCoreStateChanged(snapshot: org.json.JSONObject) {
                        client.sendReadiness(snapshot)
                    }
                })
                client.start()
            }
        }
        return START_STICKY
    }

    /** Health snapshot for the operator console HUD. */
    private fun readBattery(): org.json.JSONObject? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 1..100) {
            val sticky = registerReceiver(null, android.content.IntentFilter(
                Intent.ACTION_BATTERY_CHANGED))
            val charging = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                ?.let { it == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    it == android.os.BatteryManager.BATTERY_STATUS_FULL } ?: false
            org.json.JSONObject().put("level", level).put("charging", charging)
        } else null
    } catch (e: Throwable) {
        Log.d(TAG, "readBattery: ${e.message}")
        null
    }

    override fun onDestroy() {
        linkClient?.stop()
        linkClient = null
        appControlClaim?.stop()
        appControlClaim = null
        coreConnection?.disconnect()
        coreConnection = null
        super.onDestroy()
    }

    /** Stable per-device id: model + a persisted installation suffix. */
    private fun robotId(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val suffix = prefs.getString(KEY_SUFFIX, null) ?: run {
            val generated = Integer.toHexString((Math.random() * 0xffff).toInt())
            prefs.edit().putString(KEY_SUFFIX, generated).apply()
            generated
        }
        return "orion-${Build.MODEL}-$suffix".replace(Regex("[^A-Za-z0-9_-]"), "-")
    }

    private fun startAsForeground() {
        val channelId = "robotuth_link_v1"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "RobotUTH link", NotificationManager.IMPORTANCE_MIN)
                    .apply { setSound(null, null); enableVibration(false); setShowBadge(false) }
            )
        }
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, RobotLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentPi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivityNew::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("RobotUTH link")
            .setContentText("Kết nối điều khiển từ xa đang hoạt động")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentPi)
            .addAction(0, "Dừng", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "RobotLinkService"
        private const val NOTIF_ID = 7102
        private const val PREFS = "robot_link"
        private const val KEY_SUFFIX = "robot_id_suffix"
        const val ACTION_STOP = "com.robotuth.v3.link.STOP"

        fun start(context: Context) {
            val i = Intent(context, RobotLinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
