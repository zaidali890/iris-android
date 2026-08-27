package com.iris.android.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import com.iris.android.services.ScreenshotService
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Holds the one-time screen-capture consent so later screenshot requests don't need to re-prompt. */
object ScreenshotManager {
    var resultCode: Int? = null
    var resultData: Intent? = null

    fun hasPermission(): Boolean = resultData != null

    fun grant(code: Int, data: Intent) {
        resultCode = code
        resultData = data
    }

    private var pending: CompletableDeferred<String>? = null

    fun completeWith(pathOrError: String) {
        pending?.complete(pathOrError)
        pending = null
    }

    suspend fun capture(context: Context): String {
        if (!hasPermission()) {
            return "Screenshot capability hasn't been granted yet — open IRIS Settings and tap \"Enable Screenshots\" once."
        }
        val deferred = CompletableDeferred<String>()
        pending = deferred

        val intent = Intent(context, ScreenshotService::class.java).apply {
            putExtra("resultCode", resultCode!!)
            putExtra("resultData", resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        return deferred.await()
    }

    fun screenshotDir(context: Context): File =
        File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
}
