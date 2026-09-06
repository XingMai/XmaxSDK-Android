package com.xmax.xlab.modules.xlrealtime.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 先写完整内容再发布到相册；失败回滚 MediaStore 条目和旧系统上的目标文件。 */
internal class RecordingVideoStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun save(file: File): Uri = withContext(Dispatchers.IO) {
        val name = "XLab_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}_${UUID.randomUUID().toString().take(8)}.mp4"
        var legacyFile: File? = null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/XLab")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "XLab")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建相册目录" }
                legacyFile = File(directory, name)
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, legacyFile.absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        try {
            currentCoroutineContext().ensureActive()
            val target = checkNotNull(resolver.insert(collection, values)) { "无法创建相册视频" }
            uri = target
            checkNotNull(resolver.openOutputStream(target, "w")) { "无法写入相册视频" }.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                    "无法将录制视频发布到相册"
                }
            }
            target
        } catch (error: Throwable) {
            try { uri?.let { resolver.delete(it, null, null) } }
            catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            legacyFile?.let { if (it.exists() && !it.delete()) error.addSuppressed(IllegalStateException("无法清理未完成的相册文件")) }
            throw error
        }
    }
}
