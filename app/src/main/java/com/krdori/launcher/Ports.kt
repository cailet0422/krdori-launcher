package com.krdori.launcher

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The three local servers the modified client connects to on 127.0.0.1. */
object Ports {
    const val KAKAO = 8480
    const val SESSION = 8481
    const val GAME = 8482
    val ALL = listOf(KAKAO, SESSION, GAME)

    /** Blocking; call off the main thread. */
    fun isOpen(port: Int, timeoutMs: Int = 300): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (e: IOException) {
        false
    }

    /** Blocking; call off the main thread. */
    fun openPorts(): List<Int> = ALL.filter { isOpen(it) }
}
