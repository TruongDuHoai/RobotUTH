package com.robotuth.v3.robot.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.ainirobot.coreservice.IRobotBinderPool

/**
 * Claims CoreService app control (chassis ownership) for this app.
 *
 * Root cause this works around: on boot CoreService builds its app-control whitelist
 * (PermissionManager.initWhiteList) before the robot-setting binder is ready, so
 * `boot_app_package_name` never lands in the in-memory list; the settings-change
 * listener that would rebuild it is registered through the same not-yet-ready binder
 * and never fires either. realRegisterModule() then marks every foreground app that
 * is not the active app module as suspended, and SuspendClient denies all actions
 * with rc=-7 ("Start action permission denied : 110").
 *
 * The vendor's own grant path — SystemServer.startAppControl(pkg) →
 * CoreRunStateManager.startAppControl → setActiveAppModule + switchAppControl — is
 * exposed on binder-pool code 2 (ISystemApi) with no caller check, but the SDK jar
 * does not ship the SystemApi wrapper. So we bind CoreService exactly like
 * RobotApi.connectServer does and call ISystemApi.startAppControl (transaction 0xf)
 * over a raw parcel.
 *
 * The call no-ops server-side while the run state is SYSTEM (emergency takeover),
 * which is exactly when we must not take the chassis. Re-binding after a CoreService
 * restart re-claims automatically via onServiceConnected.
 */
class AppControlClaim(private val appContext: Context) {

    @Volatile private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.post { claim(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "CoreService binder pool disconnected; will re-claim on reconnect")
        }
    }

    fun start() {
        if (bound) return
        val intent = Intent().apply {
            setClassName(CORE_PACKAGE, "$CORE_PACKAGE.service.CoreService")
            setAction("com.ainirobot.coreservice.IRobotBinderPool")
            putExtra("client_package_name", appContext.packageName)
        }
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            Log.d(TAG, "bind CoreService failed: ${e.message}")
            false
        }
        if (!bound) Log.d(TAG, "CoreService not available; app-control claim skipped")
    }

    fun stop() {
        if (!bound) return
        bound = false
        runCatching { appContext.unbindService(connection) }
    }

    private fun claim(poolBinder: IBinder) {
        val pool = try {
            IRobotBinderPool.Stub.asInterface(poolBinder)
        } catch (e: Throwable) {
            Log.w(TAG, "binder pool wrap failed: ${e.message}")
            return
        }
        val systemBinder = try {
            pool.queryBinder(BINDER_CODE_SYSTEM, appContext.packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "queryBinder(system) failed: ${e.message}")
            return
        }
        if (systemBinder == null) {
            Log.w(TAG, "system binder unavailable from pool")
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SYSTEM_API_DESCRIPTOR)
            data.writeString(appContext.packageName)
            systemBinder.transact(TRANSACTION_START_APP_CONTROL, data, reply, 0)
            reply.readException()
            Log.i(TAG, "app control claimed for ${appContext.packageName}")
        } catch (e: Throwable) {
            Log.w(TAG, "startAppControl failed: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "AppControl"
        const val CORE_PACKAGE = "com.ainirobot.coreservice"
        const val BINDER_CODE_SYSTEM = 2
        const val SYSTEM_API_DESCRIPTOR = "com.ainirobot.coreservice.ISystemApi"
        const val TRANSACTION_START_APP_CONTROL = 0x0F
    }
}
