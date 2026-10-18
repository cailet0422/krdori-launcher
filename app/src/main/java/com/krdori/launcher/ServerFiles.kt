package com.krdori.launcher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Files shared between the UI process and the :server process.
 *
 * Layout under filesDir:
 *   krdori-v2/          server source (bundled asset or downloaded from GitHub)
 *   state/db.sqlite3    save data (KRDORI_DB_PATH), kept across server updates
 *   logs/server.log     Python stdout/stderr
 *   run/status          current server state, written by ServerService
 *   run/server-source   "bundled:<app install stamp>" or "github:<date>"
 */
object ServerFiles {
    private const val ASSET_NAME = "krdori-server.zip"
    private const val GITHUB_ZIP =
        "https://codeload.github.com/NatsuFlatWhite/krdori-v2/zip/refs/heads/main"
    private const val MARKER = "server/servers/game_server.py"
    private const val BUNDLED_PREFIX = "bundled:"
    private const val GITHUB_PREFIX = "github:"

    enum class State { STOPPED, PREPARING, STARTING, RUNNING, ERROR }

    data class Status(val state: State, val message: String)

    fun serverRoot(context: Context) = File(context.filesDir, "krdori-v2")
    fun dbFile(context: Context) = File(context.filesDir, "state/db.sqlite3")
    fun logFile(context: Context) = File(context.filesDir, "logs/server.log")
    private fun statusFile(context: Context) = File(context.filesDir, "run/status")
    private fun sourceFile(context: Context) = File(context.filesDir, "run/server-source")

    fun writeStatus(context: Context, state: State, message: String = "") {
        writeAtomically(statusFile(context), "${state.name}\n$message")
    }

    fun readStatus(context: Context): Status {
        val text = try {
            statusFile(context).takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            null
        } ?: return Status(State.STOPPED, "")
        val state = text.substringBefore('\n')
        val message = text.substringAfter('\n', "")
        return Status(State.entries.firstOrNull { it.name == state } ?: State.STOPPED, message)
    }

    /** True when the server source was downloaded from GitHub rather than bundled. */
    fun isFromGitHub(context: Context) = readSource(context)?.startsWith(GITHUB_PREFIX) == true

    /** Human readable description of the installed server source. */
    fun describeSource(context: Context): String {
        val source = readSource(context)
        return if (source != null && source.startsWith(GITHUB_PREFIX)) {
            "GitHub 최신 버전 (${source.removePrefix(GITHUB_PREFIX)} 받음)"
        } else {
            "내장 버전 (krdori-v2 @${BuildConfig.SERVER_REVISION})"
        }
    }

    /**
     * Makes sure the server source exists. The bundled copy is re-extracted after every
     * app update; a copy downloaded from GitHub is kept until the user resets it.
     */
    fun ensureExtracted(context: Context) {
        val source = readSource(context)
        val valid = File(serverRoot(context), MARKER).isFile
        if (valid && (source == bundledStamp(context) || source?.startsWith(GITHUB_PREFIX) == true)) {
            return
        }
        installBundled(context)
    }

    fun installBundled(context: Context) {
        context.assets.open(ASSET_NAME).use { replaceServerDir(context, it, stripRootDir = false) }
        writeSource(context, bundledStamp(context))
    }

    fun installFromGitHub(context: Context) {
        val connection = URL(GITHUB_ZIP).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub 응답 오류: HTTP ${connection.responseCode}")
            }
            // GitHub archives wrap everything in a "krdori-v2-main/" directory.
            connection.inputStream.use { replaceServerDir(context, it, stripRootDir = true) }
        } finally {
            connection.disconnect()
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        writeSource(context, GITHUB_PREFIX + date)
    }

    private fun replaceServerDir(context: Context, input: InputStream, stripRootDir: Boolean) {
        val root = serverRoot(context)
        val tmp = File(context.filesDir, "krdori-v2.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = if (stripRootDir) entry.name.substringAfter('/', "") else entry.name
                if (name.isEmpty()) continue
                val out = File(tmp, name)
                if (!out.canonicalPath.startsWith(tmpPath)) throw IOException("잘못된 압축 항목: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        if (!File(tmp, MARKER).isFile) {
            tmp.deleteRecursively()
            throw IOException("서버 파일을 찾을 수 없습니다 ($MARKER)")
        }
        root.deleteRecursively()
        if (!tmp.renameTo(root)) throw IOException("서버 폴더를 교체하지 못했습니다")
    }

    /** Copies the save data into [out] as a single SQLite file. The server must be stopped. */
    fun backupDb(context: Context, out: OutputStream) {
        val db = dbFile(context)
        if (!db.isFile) throw IOException("아직 저장된 데이터가 없습니다")
        // Fold the WAL file back into the main database before copying it.
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        db.inputStream().use { it.copyTo(out) }
    }

    /** Replaces the save data with an SQLite file. The server must be stopped. */
    fun restoreDb(context: Context, input: InputStream) {
        val db = dbFile(context)
        db.parentFile?.mkdirs()
        val tmp = File(db.parentFile, "restore.tmp")
        tmp.outputStream().use { input.copyTo(it) }
        val header = ByteArray(16)
        val read = tmp.inputStream().use { it.read(header) }
        if (read != 16 || String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") {
            tmp.delete()
            throw IOException("SQLite 데이터베이스 파일이 아닙니다")
        }
        File(db.path + "-wal").delete()
        File(db.path + "-shm").delete()
        db.delete()
        if (!tmp.renameTo(db)) throw IOException("데이터를 복원하지 못했습니다")
    }

    /** Returns roughly the last [maxBytes] of the server log. */
    fun readLogTail(context: Context, maxBytes: Int = 48 * 1024): String {
        val file = logFile(context)
        if (!file.isFile) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - maxBytes).coerceAtLeast(0)
                val buffer = ByteArray((length - start).toInt())
                raf.seek(start)
                // The server may truncate the file while we read it.
                val read = raf.read(buffer).coerceAtLeast(0)
                val text = String(buffer, 0, read, Charsets.UTF_8)
                if (start > 0) text.substringAfter('\n') else text
            }
        } catch (e: IOException) {
            ""
        }
    }

    fun clearLog(context: Context) {
        val file = logFile(context)
        if (file.isFile) RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    private fun readSource(context: Context): String? =
        sourceFile(context).takeIf { it.isFile }?.readText()?.trim()

    private fun writeSource(context: Context, value: String) = writeAtomically(sourceFile(context), value)

    @Suppress("DEPRECATION")
    private fun bundledStamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return BUNDLED_PREFIX + info.lastUpdateTime
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }
}
