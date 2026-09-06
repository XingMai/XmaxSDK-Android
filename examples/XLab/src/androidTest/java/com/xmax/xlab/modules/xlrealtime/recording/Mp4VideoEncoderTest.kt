package com.xmax.xlab.modules.xlrealtime.recording

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 MediaCodec/MediaMuxer 验证；只在测试结束时删除本测试创建的文件和相册条目。 */
@RunWith(AndroidJUnit4::class)
class Mp4VideoEncoderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun finalI420FramesProduceDecodableSilentMp4WithNormalizedTimestampsAndRotation() {
        val file = encodeClip()
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                extractor.selectTrack(0)
                var count = 0
                var previous = -1L
                while (extractor.sampleTime >= 0) {
                    val time = extractor.sampleTime
                    if (count == 0) assertEquals(0L, time)
                    assertTrue(time > previous)
                    previous = time
                    count++
                    extractor.advance()
                }
                assertEquals(6, count)
            } finally { extractor.release() }
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.absolutePath)
                assertEquals("90", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 200)
                val bitmap = checkNotNull(metadata.getFrameAtTime(0))
                try {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue(Color.red(color) > Color.green(color) + 80)
                    assertTrue(Color.red(color) > Color.blue(color) + 80)
                } finally { bitmap.recycle() }
            } finally { metadata.release() }
        } finally { file.delete() }
    }

    @Test fun completedVideoIsPublishedAsReadableMediaStoreVideo() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val file = encodeClip()
        var uri: android.net.Uri? = null
        try {
            val saved = RecordingVideoStore(context).save(file)
            uri = saved
            context.contentResolver.query(saved,
                arrayOf(MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.MIME_TYPE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals("video/mp4", cursor.getString(1))
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, saved, null)
                assertEquals(1, extractor.trackCount)
            } finally { extractor.release() }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }

    private fun encodeClip(): File {
        val file = File.createTempFile("xlab-recording-test-", ".mp4", context.cacheDir)
        try {
            Mp4VideoEncoder(file, RecordingFormat(320, 240, 90)).use { encoder ->
                repeat(6) { index ->
                    encoder.append(RecordingFrame(320, 240, 90, 9_000_000L + index * 40_000L, 40_000L,
                        ByteBuffer.wrap(ByteArray(320 * 240) { 81 }),
                        ByteBuffer.wrap(ByteArray(160 * 120) { 90 }),
                        ByteBuffer.wrap(ByteArray(160 * 120) { 240.toByte() })))
                }
                encoder.finish()
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}
