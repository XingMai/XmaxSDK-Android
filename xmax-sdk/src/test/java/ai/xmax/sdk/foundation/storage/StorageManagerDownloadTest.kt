package ai.xmax.sdk.foundation.storage

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageManagerDownloadTest {
    @Test
    fun `successful download replaces destination`() = runTest {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin").apply {
                writeText("original")
            }
            val downloaded = "complete download".toByteArray()

            val result = downloadFile(
                remoteUrl = TEST_URL,
                destination = destination,
                progress = null,
                connectionFactory = {
                    DownloadConnection(downloaded, downloaded.size.toLong())
                },
            )

            assertArrayEquals(downloaded, destination.readBytes())
            assertEquals(downloaded.size.toLong(), result.byteCount)
            assertNoStagingFiles(directory)
        }
    }

    @Test
    fun `stream failure preserves existing destination and removes staging file`() = runTest {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin").apply {
                writeText("original")
            }

            val error = expectDownloadError {
                downloadFile(
                    remoteUrl = TEST_URL,
                    destination = destination,
                    progress = null,
                    connectionFactory = {
                        DownloadConnection(
                            body = "partial".toByteArray(),
                            declaredLength = 20L,
                            failsAfterBody = true,
                        )
                    },
                )
            }

            assertEquals(XmaxErrorCode.DOWNLOAD_ERROR, error.code)
            assertEquals("original", destination.readText())
            assertNoStagingFiles(directory)
        }
    }

    @Test
    fun `truncated response preserves existing destination`() = runTest {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin").apply {
                writeText("original")
            }

            val error = expectDownloadError {
                downloadFile(
                    remoteUrl = TEST_URL,
                    destination = destination,
                    progress = null,
                    connectionFactory = {
                        DownloadConnection(
                            body = "short".toByteArray(),
                            declaredLength = 20L,
                        )
                    },
                )
            }

            assertEquals(XmaxErrorCode.DOWNLOAD_ERROR, error.code)
            assertEquals("original", destination.readText())
            assertNoStagingFiles(directory)
        }
    }

    private suspend fun expectDownloadError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }

    private fun assertNoStagingFiles(directory: File) {
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".xmax-download-") })
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("xmax-storage-download-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class DownloadConnection(
        private val body: ByteArray,
        private val declaredLength: Long,
        private val failsAfterBody: Boolean = false,
    ) : HttpURLConnection(URL(TEST_URL)) {
        override fun getResponseCode(): Int = HTTP_OK

        override fun getContentLengthLong(): Long = declaredLength

        override fun getInputStream(): InputStream = if (failsAfterBody) {
            FailingInputStream(body)
        } else {
            ByteArrayInputStream(body)
        }

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false
    }

    private class FailingInputStream(
        private val body: ByteArray,
    ) : InputStream() {
        private var delivered = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered) throw IOException("connection interrupted")
            delivered = true
            val count = minOf(body.size, length)
            body.copyInto(buffer, offset, 0, count)
            return count
        }
    }

    private companion object {
        const val TEST_URL = "https://assets.example.com/media.bin"
    }
}
