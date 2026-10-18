package com.krdori.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Finds, launches and installs the modified game client. */
object GameApp {
    /** The modified client first; some builds keep the original package name. */
    private val PACKAGES = listOf("com.kakaogames.bangdreamkr.local", "com.kakaogames.bangdreamkr")

    data class Installed(val packageName: String, val versionName: String?)

    @Suppress("DEPRECATION")
    fun installed(context: Context): Installed? {
        for (name in PACKAGES) {
            try {
                val info = context.packageManager.getPackageInfo(name, 0)
                return Installed(name, info.versionName)
            } catch (e: PackageManager.NameNotFoundException) {
                // Try the next candidate.
            }
        }
        return null
    }

    fun launchIntent(context: Context): Intent? {
        val game = installed(context) ?: return null
        return context.packageManager.getLaunchIntentForPackage(game.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launch(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        context.startActivity(intent)
        return true
    }

    /** Streams the APK at [uri] into a PackageInstaller session. Blocking; call off the main thread. */
    fun install(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val input = resolver.openInputStream(uri) ?: error("APK 파일을 열 수 없습니다")
                input.use {
                    session.openWrite("base.apk", 0, length).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java), flags
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }
}

/** Receives PackageInstaller results and shows the system confirmation screen when needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.install_success, Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Toast.makeText(context, R.string.install_conflict, Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.install_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
