package com.krdori.launcher

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.krdori.launcher.ServerFiles.State
import kotlin.concurrent.thread

/**
 * Foreground service hosting the embedded Python server. It lives in the ":server"
 * process: Python threads cannot be stopped cleanly, so stopping kills the process.
 */
class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        private const val ACTION_START = "com.krdori.launcher.action.START"
        private const val ACTION_STOP = "com.krdori.launcher.action.STOP"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ServerService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }

        /** Whether the service is alive (in any process of this app). */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            // Still returns the caller's own services on current Android versions.
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ServerService::class.java.name }
        }
    }

    private var started = false

    @Volatile
    private var notificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        // Every startForegroundService() call must be answered with startForeground().
        showForeground(notificationText.ifEmpty { getString(R.string.notif_preparing) })
        if (!started) {
            started = true
            thread(name = "krdori-start") { runServer() }
        }
        return START_NOT_STICKY
    }

    private fun runServer() {
        try {
            ServerFiles.writeStatus(this, State.PREPARING, getString(R.string.status_extracting))
            ServerFiles.ensureExtracted(this)

            ServerFiles.writeStatus(this, State.STARTING, getString(R.string.status_python))
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val launcher = Python.getInstance().getModule("krdori_launcher")
            launcher.callAttr(
                "start",
                ServerFiles.serverRoot(this).path,
                ServerFiles.dbFile(this).path,
                ServerFiles.logFile(this).path,
            )
            ServerFiles.writeStatus(this, State.STARTING, getString(R.string.status_binding))
            monitor(launcher)
        } catch (e: Throwable) {
            Log.e(TAG, "Server failed to start", e)
            fail(e.message ?: e.toString())
        }
    }

    /** Reports RUNNING once all ports accept connections, then watches for crashed server threads. */
    private fun monitor(launcher: PyObject) {
        var running = false
        while (true) {
            val errors = launcher.callAttr("errors").toString()
            if (errors.isNotEmpty()) {
                fail(errors)
                return
            }
            if (!running) {
                val open = Ports.openPorts()
                if (open.size == Ports.ALL.size) {
                    running = true
                    ServerFiles.writeStatus(this, State.RUNNING, getString(R.string.status_running_detail))
                    updateNotification(getString(R.string.notif_running))
                } else {
                    ServerFiles.writeStatus(
                        this, State.STARTING,
                        getString(R.string.status_waiting_ports, open.size, Ports.ALL.size)
                    )
                }
            }
            Thread.sleep(if (running) 3000 else 500)
        }
    }

    private fun fail(message: String) {
        ServerFiles.writeStatus(this, State.ERROR, message)
        updateNotification(getString(R.string.notif_error))
    }

    private fun stopServer() {
        ServerFiles.writeStatus(this, State.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Python's server threads can't be interrupted; ending the process frees the ports.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    private fun showForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        notificationText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop_server), stop)
        GameApp.launchIntent(this)?.let { game ->
            val play = PendingIntent.getActivity(this, 2, game, immutable or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, getString(R.string.action_launch_game), play)
        }
        return builder.build()
    }
}
