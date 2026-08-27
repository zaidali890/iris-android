package com.iris.android.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.iris.android.IrisApplication
import com.iris.android.tools.ScreenshotManager
import java.io.File
import java.io.FileOutputStream

class ScreenshotService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, IrisApplication.CHANNEL_ASSISTANT)
            .setContentTitle("IRIS is capturing a screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                42,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(42, notification)
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra("resultData")

        if (resultCode == -1 || resultData == null) {
            ScreenshotManager.completeWith("Screenshot permission data was missing.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = projectionManager.getMediaProjection(resultCode, resultData)

            val metrics = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).displays.firstOrNull()
            display?.getRealMetrics(metrics)
            val width = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val height = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val density = metrics.densityDpi.takeIf { it > 0 } ?: 320

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "iris-screenshot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        val dir = ScreenshotManager.screenshotDir(this)
                        val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        ScreenshotManager.completeWith(file.absolutePath)
                    } catch (e: Exception) {
                        ScreenshotManager.completeWith("Screenshot capture failed: ${e.message}")
                    } finally {
                        image.close()
                        teardown()
                        stopSelf()
                    }
                }
            }, null)
        } catch (e: Exception) {
            ScreenshotManager.completeWith("Screenshot capture failed: ${e.message}")
            teardown()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun teardown() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
