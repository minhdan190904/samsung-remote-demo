package com.example.samsung_remote_test.mirror

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

object JpegFactory {
    fun black(width: Int, height: Int, quality: Int = 70): ByteArray {
        val bmp = createBitmap(width, height)
        val os = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), os)
        bmp.recycle()
        return os.toByteArray()
    }
}