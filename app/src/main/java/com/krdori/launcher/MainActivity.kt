package com.krdori.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.krdori.launcher.ServerFiles.State
import com.krdori.launcher.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var busyJob: Job? = null
    private var askedNotificationPermission = false

    /** Set while the user is in the "install unknown apps" settings screen. */
    private var pendingApkPick = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::installApk)
    }

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let(::backupTo)
        }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::confirmRestore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.playButton.setOnClickListener { oneClickPlay() }
        binding.startButton.setOnClickListener { startServer() }
        binding.stopButton.setOnClickListener { runBusy { stopServerAndWait() } }
        binding.launchGameButton.setOnClickListener { launchGame() }
        binding.installApkButton.setOnClickListener { chooseApk() }
        binding.backupButton.setOnClickListener {
            withServerStopped { createBackup.launch("krdori-save-${timestamp()}.sqlite3") }
        }
        binding.restoreButton.setOnClickListener { pickBackup.launch(arrayOf("*/*")) }
        binding.updateServerButton.setOnClickListener { confirmServerUpdate() }
        binding.resetServerButton.setOnClickListener {
            withServerStopped {
                withContext(Dispatchers.IO) { ServerFiles.installBundled(this@MainActivity) }
                toast(R.string.reset_done)
            }
        }
        binding.logButton.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingApkPick) {
            pendingApkPick = false
            if (canInstallPackages()) launchApkPicker()
        }
    }

    // ---- One-click flow ----------------------------------------------------------------

    private fun oneClickPlay() {
        if (GameApp.installed(this) == null) {
            showGameMissing()
            return
        }
        requestNotificationPermission()
        runBusy {
            if (ensureServerRunning() && !GameApp.launch(this)) toast(R.string.game_launch_failed)
        }
    }

    private fun startServer() {
        requestNotificationPermission()
        runBusy { ensureServerRunning() }
    }

    /** Starts the server if needed and waits until all three ports are up. */
    private suspend fun ensureServerRunning(): Boolean {
        if (ServerService.isRunning(this)) {
            when (ServerFiles.readStatus(this).state) {
                State.RUNNING -> return true
                State.ERROR -> stopServerAndWait()
                else -> return waitForServer()
            }
        }
        // Overwrite whatever a previous (possibly killed) server process left behind.
        ServerFiles.writeStatus(this, State.PREPARING, getString(R.string.status_extracting))
        ServerService.start(this)
        return waitForServer()
    }

    private suspend fun waitForServer(): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < 180_000) {
            val status = ServerFiles.readStatus(this)
            val alive = ServerService.isRunning(this)
            when {
                status.state == State.RUNNING && alive -> return true
                status.state == State.ERROR -> {
                    val reason = if ("Address already in use" in status.message || "10048" in status.message) {
                        getString(R.string.port_in_use)
                    } else {
                        status.message
                    }
                    showError(getString(R.string.server_failed, reason))
                    return false
                }
                !alive && SystemClock.elapsedRealtime() - startedAt > 5_000 -> {
                    showError(getString(R.string.server_died))
                    return false
                }
            }
            delay(400)
        }
        showError(getString(R.string.server_timeout))
        return false
    }

    private suspend fun stopServerAndWait() {
        if (!ServerService.isRunning(this)) return
        ServerService.stop(this)
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < 10_000) {
            val portsOpen = withContext(Dispatchers.IO) { Ports.openPorts().isNotEmpty() }
            if (!ServerService.isRunning(this) && !portsOpen) break
            delay(250)
        }
        ServerFiles.writeStatus(this, State.STOPPED)
    }

    // ---- Game --------------------------------------------------------------------------

    private fun launchGame() {
        if (!GameApp.launch(this)) showGameMissing()
    }

    private fun showGameMissing() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.game_missing_title)
            .setMessage(R.string.game_missing_message)
            .setPositiveButton(R.string.choose_apk) { _, _ -> chooseApk() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseApk() {
        if (!canInstallPackages()) {
            // The picker opens by itself once the user comes back with the permission granted.
            pendingApkPick = true
            toast(R.string.unknown_sources)
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        launchApkPicker()
    }

    private fun canInstallPackages() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun launchApkPicker() {
        pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
    }

    private fun installApk(uri: Uri) {
        runBusy(getString(R.string.installing)) {
            withContext(Dispatchers.IO) { GameApp.install(this@MainActivity, uri) }
        }
    }

    // ---- Save data ---------------------------------------------------------------------

    private fun backupTo(uri: Uri) {
        runBusy {
            withContext(Dispatchers.IO) {
                val out = contentResolver.openOutputStream(uri, "wt") ?: error("파일을 열 수 없습니다")
                out.use { ServerFiles.backupDb(this@MainActivity, it) }
            }
            toast(R.string.backup_done)
        }
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_message)
            .setPositiveButton(R.string.restore) { _, _ ->
                withServerStopped {
                    withContext(Dispatchers.IO) {
                        val input = contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다")
                        input.use { ServerFiles.restoreDb(this@MainActivity, it) }
                    }
                    toast(R.string.restore_done)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Server source -----------------------------------------------------------------

    private fun confirmServerUpdate() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_confirm_title)
            .setMessage(R.string.update_confirm_message)
            .setPositiveButton(R.string.proceed) { _, _ ->
                withServerStopped(getString(R.string.updating)) {
                    withContext(Dispatchers.IO) { ServerFiles.installFromGitHub(this@MainActivity) }
                    toast(R.string.update_done)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Helpers -----------------------------------------------------------------------

    /** Runs [action] after stopping the server, asking first if it is running. */
    private fun withServerStopped(message: String? = null, action: suspend () -> Unit) {
        if (!ServerService.isRunning(this)) {
            runBusy(message) { action() }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_needed_title)
            .setMessage(R.string.stop_needed_message)
            .setPositiveButton(R.string.proceed) { _, _ ->
                runBusy(message) {
                    stopServerAndWait()
                    action()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs one long operation at a time with the buttons disabled. */
    private fun runBusy(message: String? = null, block: suspend () -> Unit) {
        if (busyJob?.isActive == true) return
        busyJob = lifecycleScope.launch {
            setBusy(true, message)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: e.toString())
            } finally {
                setBusy(false, null)
            }
        }
    }

    private var busyMessage: String? = null

    private fun setBusy(busy: Boolean, message: String?) {
        busyMessage = message
        binding.progress.isVisible = busy
        listOf(
            binding.playButton, binding.startButton, binding.stopButton, binding.launchGameButton,
            binding.installApkButton, binding.backupButton, binding.restoreButton,
            binding.updateServerButton, binding.resetServerButton,
        ).forEach { it.isEnabled = !busy }
    }

    private suspend fun refresh() {
        val status = ServerFiles.readStatus(this)
        val alive = ServerService.isRunning(this)
        val state = when {
            alive && status.state == State.STOPPED -> State.STARTING
            alive -> status.state
            status.state == State.ERROR -> State.ERROR
            else -> State.STOPPED
        }
        val crashed = !alive && status.state in setOf(State.PREPARING, State.STARTING, State.RUNNING)

        val (title, color) = when (state) {
            State.RUNNING -> R.string.state_running to R.color.status_ok
            State.PREPARING, State.STARTING -> R.string.state_starting to R.color.status_wait
            State.ERROR -> R.string.state_error to R.color.status_error
            State.STOPPED -> R.string.state_stopped to R.color.status_off
        }
        binding.statusText.setText(title)
        binding.statusText.setTextColor(ContextCompat.getColor(this, color))
        binding.statusDetail.text = when {
            busyMessage != null -> busyMessage
            crashed -> getString(R.string.state_crashed_detail)
            state == State.STOPPED -> getString(R.string.state_stopped_detail)
            else -> status.message
        }
        if (state == State.PREPARING || state == State.STARTING) binding.progress.isVisible = true
        else if (busyJob?.isActive != true) binding.progress.isVisible = false
        val portColor = ContextCompat.getColor(this, if (state == State.RUNNING) R.color.status_ok else R.color.status_off)
        listOf(binding.portKakao, binding.portSession, binding.portGame).forEach { it.setTextColor(portColor) }

        val game = GameApp.installed(this)
        binding.gameInfo.text = if (game != null) {
            getString(R.string.game_installed, game.packageName, game.versionName ?: "?")
        } else {
            getString(R.string.game_missing)
        }
        binding.launchGameButton.isVisible = game != null

        binding.dataInfo.text = withContext(Dispatchers.IO) { describeSaveData() }
        binding.serverInfo.text = ServerFiles.describeSource(this)
        binding.resetServerButton.isVisible = ServerFiles.isFromGitHub(this)

        val power = getSystemService(PowerManager::class.java)
        binding.batteryButton.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun describeSaveData(): String {
        val db = ServerFiles.dbFile(this)
        if (!db.isFile) return getString(R.string.data_none)
        val wal = File(db.path + "-wal")
        val size = db.length() + (if (wal.isFile) wal.length() else 0)
        val modified = maxOf(db.lastModified(), if (wal.isFile) wal.lastModified() else 0)
        val ago = DateUtils.getRelativeTimeSpanString(modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return getString(R.string.data_info, Formatter.formatShortFileSize(this, size), ago)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedNotificationPermission) return
        askedNotificationPermission = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun showError(message: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.view_log) { _, _ -> startActivity(Intent(this, LogActivity::class.java)) }
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}
