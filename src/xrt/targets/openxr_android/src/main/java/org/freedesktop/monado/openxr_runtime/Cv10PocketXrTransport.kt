// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.util.Log
import android.view.Surface
import androidx.annotation.MainThread
import java.util.concurrent.Executors
import org.freedesktop.monado.auxiliary.Cv10PocketXrDisplayService
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay
import rikka.shizuku.Shizuku

/** Opt-in, single-use session. The caller owns the output Surface and closes this on exit. */
@MainThread
internal class Cv10PocketXrTransport(
    context: Context,
    private val mode: Int,
    private val onInterrupted: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val clientLife = Binder()
    private val args =
        Shizuku.UserServiceArgs(ComponentName(this.context, Cv10PocketXrDisplayService::class.java))
            .tag("cv10_pocket_xr_display")
            .processNameSuffix("cv10_pocket_xr_display")
            .daemon(false)
            .version(7)
    private var connection: ServiceConnection? = null
    private var remote: ICv10PocketXrDisplay? = null
    private var startupResult: ((Result<Int>) -> Unit)? = null
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var closing = false
    private var closed = false
    private val startTimeout = Runnable { fail(IllegalStateException("Pocket XR display startup timed out")) }

    /** Reports Android's acceptance of the fixed Activity launch, before the first frame. */
    fun start(surface: Surface, onResult: (Result<Int>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closing && startupResult == null && connection == null) { "Pocket XR transport is single-use" }
        startupResult = onResult
        try {
            check(activeSession == null) { "A Pocket XR display session is already opening or closing" }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "Pocket XR virtual display requires Android 14 or later"
            }
            check(context.getSystemService(UserManager::class.java)?.isSystemUser == true) {
                "Pocket XR virtual display requires the device owner profile"
            }
            check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
                "Android does not support launching activities on secondary displays"
            }
            require(surface.isValid) { "Pocket XR output Surface is invalid" }
            check(Shizuku.pingBinder()) { "Start Shizuku before opening Pocket XR in the virtual display" }
            check(Shizuku.getVersion() >= 13 && Shizuku.getUid() == Process.SHELL_UID) {
                "Pocket XR virtual display requires Shizuku 13+ running as the wireless-debugging shell"
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Allow Monado CV10 in Shizuku before opening the virtual display")
            }
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    main.post {
                        if (this != connection || closing || remote != null) return@post
                        val service = ICv10PocketXrDisplay.Stub.asInterface(binder)
                        remote = service
                        worker.execute {
                            val result = try {
                                Result.success(service.start(surface, clientLife, mode))
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                            main.post { reportStart(result) }
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    main.post {
                        if (this == connection) fail(IllegalStateException("Pocket XR display service disconnected"))
                    }
                }
            }
            activeSession = this
            connection = serviceConnection
            main.postDelayed(startTimeout, 10_000)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun reportStart(result: Result<Int>) {
        if (closing) return
        val error = result.exceptionOrNull()
        if (error != null) {
            fail(error)
            return
        }
        main.removeCallbacks(startTimeout)
        val callback = startupResult
        startupResult = null
        callback?.invoke(result)
    }

    fun openClientDisplay(onResult: (Result<Int>) -> Unit) {
        val service = remote
        if (closing || service == null) {
            onResult(Result.failure(IllegalStateException("The Pocket XR display service is unavailable")))
            return
        }
        worker.execute {
            val result = runCatching { service.openClientDisplay(clientLife) }
            main.post { if (!closing) onResult(result) }
        }
    }

    fun closeClientDisplay(onResult: (Result<Unit>) -> Unit) {
        val service = remote
        if (closing || service == null) {
            onResult(Result.failure(IllegalStateException("The Pocket XR display service is unavailable")))
            return
        }
        worker.execute {
            val result = runCatching { service.closeClientDisplay(clientLife) }
            result.onFailure { error ->
                Log.w(TAG, "Pocket XR client display release failed", error)
            }
            main.post { if (!closing) onResult(result) }
        }
    }

    private fun fail(error: Throwable) {
        if (closing) return
        Log.w(TAG, "Pocket XR display unavailable", error)
        val callback = startupResult
        close()
        if (callback == null) onInterrupted(error) else callback(Result.failure(error))
    }

    override fun close() = close {}

    /** Calls onClosed on the main thread after the pending remote start/stop and unbind finish. */
    fun close(onClosed: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) {
            onClosed()
            return
        }
        closeCallbacks.add(onClosed)
        if (closing) return
        closing = true
        main.removeCallbacks(startTimeout)
        startupResult = null
        val service = remote
        val serviceConnection = connection
        remote = null
        connection = null
        worker.execute {
            try {
                service?.stop(clientLife)
            } catch (e: Exception) {
                Log.w(TAG, "Pocket XR display stop call failed", e)
            } finally {
                main.post {
                    try {
                        if (serviceConnection != null) Shizuku.unbindUserService(args, serviceConnection, false)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Pocket XR display service unbind failed", e)
                    }
                    closed = true
                    if (activeSession == this) activeSession = null
                    val callbacks = closeCallbacks.toList()
                    closeCallbacks.clear()
                    callbacks.forEach { it() }
                }
            }
        }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "Cv10PocketXrTransport"
        // Shizuku 13.1.5 shares the connection slot for a UserService across callers in this process.
        var activeSession: Cv10PocketXrTransport? = null
    }
}
