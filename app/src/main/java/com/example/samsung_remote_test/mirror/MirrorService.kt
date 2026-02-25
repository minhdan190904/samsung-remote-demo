package com.example.samsung_remote_test.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.samsung_remote_test.MainActivity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import androidx.core.graphics.createBitmap
import fi.iki.elonen.NanoHTTPD

class MirrorService : Service() {

    companion object {
        const val ACTION_START = "mirror.action.START"
        const val ACTION_STOP = "mirror.action.STOP"
        const val EXTRA_RESULT_CODE = "mirror.extra.RESULT_CODE"
        const val EXTRA_DATA_INTENT = "mirror.extra.DATA_INTENT"
        const val EXTRA_HTTP_PORT = "mirror.extra.HTTP_PORT"
        const val EXTRA_FPS = "mirror.extra.FPS"

        private const val CH_ID = "mirror_channel"
        private const val NOTI_ID = 1001
        private const val TAG = "MirrorService"
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var httpPort: Int = 8899
    private var fps: Int = 12

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    private var frameCount = 0L
    private var lastFrameAt = 0L

    private fun startMirroring(resultCode: Int, data: Intent) {
        if (projection != null) return

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        if (projection == null) {
            stopSelf()
            return
        }

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopMirroring("projection.onStop")
                stopSelf()
            }
        }, null)

        val (w, h, dpi) = getCaptureSize()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        reader?.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            runCatching {
                val jpeg = imageToJpeg(img, w, h, 70)
                if (jpeg != null) {
                    MirrorStreamHub.setFrameJpeg(jpeg)
                    frameCount++
                    val now = android.os.SystemClock.elapsedRealtime()
                    lastFrameAt = now
                    if (frameCount % 30L == 0L) {
                        Log.d(TAG, "frame#$frameCount jpeg=${jpeg.size}B w=$w h=$h")
                    }
                }
            }
            runCatching { img.close() }
        }, null)

        vd = projection?.createVirtualDisplay(
            "mirror",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            null
        )

        Log.d(TAG, "Started capture w=$w h=$h dpi=$dpi")
    }

    private fun stopMirroring(reason: String) {
        Log.d(TAG, "stopMirroring reason=$reason")
        runCatching { vd?.release() }
        vd = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { MirrorStreamHub.stop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        Log.d(TAG, "onStartCommand action=$act")

        when (act) {
            ACTION_STOP -> {
                stopMirroring("ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
                httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, httpPort)
                fps = intent.getIntExtra(EXTRA_FPS, fps)

                if (resultCode == 0 || data == null) {
                    Log.e(TAG, "Missing MediaProjection permission result")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startMirroring(resultCode, data)
            }

            else -> {
                Log.w(TAG, "Unknown action: $act")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopMirroring("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "Screen Mirroring", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopPi = PendingIntent.getService(
            this,
            2,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val noti: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Mirroring")
            .setContentText("Đang chia sẻ màn hình")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun getCaptureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = resources.displayMetrics
            dm.setTo(m)
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }

        val srcW = dm.widthPixels.coerceAtLeast(1)
        val srcH = dm.heightPixels.coerceAtLeast(1)
        val maxW = 1280f
        val maxH = 720f
        val scale = min(1f, min(maxW / srcW.toFloat(), maxH / srcH.toFloat()))
        val w = ((srcW * scale).toInt() / 2) * 2
        val h = ((srcH * scale).toInt() / 2) * 2
        return Triple(w.coerceAtLeast(2), h.coerceAtLeast(2), dm.densityDpi.coerceAtLeast(1))
    }

    private fun imageToJpeg(image: Image, w: Int, h: Int, quality: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bmpW = w + rowPadding / pixelStride
        val bitmap = createBitmap(bmpW, h)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, w, h)
        if (cropped != bitmap) bitmap.recycle()

        val os = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), os)
        cropped.recycle()
        return os.toByteArray()
    }
}