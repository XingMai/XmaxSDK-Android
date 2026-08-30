package com.xmax.xlab.modules.xlrealtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxLoggerOption
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RealtimeReferenceUploader(
    context: Context,
    apiKey: String,
) {
    private val applicationContext = context.applicationContext
    private val storageManager by lazy {
        XmaxClient(
            applicationContext,
            XmaxConfiguration(
                apiKey = apiKey,
                loggerOptions = XmaxLoggerOption.business,
            ),
        ).createStorageManager()
    }

    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        val mimeType = resolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val extension = displayName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?: extensionForMimeType(mimeType)
        val directory = File(applicationContext.cacheDir, "realtime_references").apply {
            check(exists() || mkdirs()) { "无法创建参考图缓存目录" }
        }
        val localFile = File(
            directory,
            "realtime_reference_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension",
        )

        try {
            resolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选参考图")
            storageManager.uploadImageFile(localFile, mimeType).url
        } finally {
            localFile.delete()
        }
    }
}

private fun extensionForMimeType(mimeType: String): String = when (mimeType) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic", "image/heif" -> "heic"
    "image/bmp" -> "bmp"
    "image/tiff" -> "tiff"
    "image/avif" -> "avif"
    else -> "jpg"
}
