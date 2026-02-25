package com.example.samsung_remote_test.mirror

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object MirrorStreamHub {
    private const val TAG = "MirrorHub"

    private val currentPort = AtomicInteger(-1)
    private val currentFps = AtomicInteger(12)
    private val serverRef = AtomicReference<MjpegServer?>(null)

    fun start(port: Int, fps: Int): Boolean {
        val s = serverRef.get()
        if (s != null && currentPort.get() == port && currentFps.get() == fps) return true

        stop()

        return runCatching {
            val ns = MjpegServer(port, fps)
            ns.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            currentPort.set(port)
            currentFps.set(fps)
            serverRef.set(ns)
            Log.d(TAG, "Server started port=$port fps=$fps")
            true
        }.onFailure {
            Log.e(TAG, "Server start failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { serverRef.getAndSet(null)?.stop() }
        Log.d(TAG, "Server stopped")
        currentPort.set(-1)
    }

    fun setFrameJpeg(jpeg: ByteArray) {
        serverRef.get()?.setFrameJpeg(jpeg)
    }
}