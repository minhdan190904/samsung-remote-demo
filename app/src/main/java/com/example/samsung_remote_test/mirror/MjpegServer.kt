package com.example.samsung_remote_test.mirror

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(
    port: Int,
    private val fps: Int
) : NanoHTTPD(port) {

    private val latestJpeg = AtomicReference<ByteArray?>(null)

    fun setFrameJpeg(jpeg: ByteArray) {
        latestJpeg.set(jpeg)
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d("MjpegServer", "uri=${session.uri} headers=${session.headers}")

        return when (session.uri) {
            "/ping" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

            "/" -> {
                val html = """
                    <!doctype html>
                    <html>
                    <head>
                      <meta name="viewport" content="width=device-width, initial-scale=1"/>
                      <style>
                        html,body{margin:0;padding:0;background:#000;height:100%}
                        img{width:100vw;height:100vh;object-fit:contain}
                      </style>
                    </head>
                    <body>
                      <img src="/mjpeg"/>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }

            "/mjpeg" -> {
                val pin = PipedInputStream(256 * 1024)
                val pout = PipedOutputStream(pin)

                val boundary = "frame"
                val mime = "multipart/x-mixed-replace; boundary=$boundary"

                Thread {
                    val delayMs = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(10L)
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val frame = latestJpeg.get()
                            if (frame == null) {
                                Thread.sleep(20)
                                continue
                            }

                            val header = buildString {
                                append("--").append(boundary).append("\r\n")
                                append("Content-Type: image/jpeg\r\n")
                                append("Content-Length: ").append(frame.size).append("\r\n")
                                append("\r\n")
                            }.toByteArray(Charsets.US_ASCII)

                            pout.write(header)
                            pout.write(frame)
                            pout.write("\r\n".toByteArray(Charsets.US_ASCII))
                            pout.flush()

                            Thread.sleep(delayMs)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try { pout.close() } catch (_: IOException) {}
                    }
                }.apply { isDaemon = true }.start()

                newChunkedResponse(Response.Status.OK, mime, pin)
            }

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }
}