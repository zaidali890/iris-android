package com.iris.android.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

object VoskModelManager {
    // The "small" English model — good balance of size (~40MB) vs accuracy for a constrained
    // wake-word vocabulary. Larger/other-language models are listed at alphacephei.com/vosk/models
    // if this one doesn't suit — just change MODEL_URL and MODEL_DIR_NAME to match.
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.exists() && dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }

    /** Downloads and unpacks the model, reporting 0-100 progress. Safe to call again if it failed
     * partway — just re-downloads. Runs on IO dispatcher; call from a coroutine. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder().url(MODEL_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }
            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val total = body.contentLength()

            val zipFile = File(context.cacheDir, "vosk-model.zip")
            var bytesRead = 0L
            body.byteStream().use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (total > 0) onProgress(((bytesRead * 100) / total).toInt())
                    }
                }
            }

            unzip(zipFile, context.filesDir)
            zipFile.delete()
            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zis.copyTo(output) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
