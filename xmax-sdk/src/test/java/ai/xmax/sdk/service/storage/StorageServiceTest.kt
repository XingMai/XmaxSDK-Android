package ai.xmax.sdk.service.storage

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageConfiguration
import ai.xmax.sdk.foundation.storage.StorageManaging
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StorageSource
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.service.network.ApiMethod
import ai.xmax.sdk.service.network.ApiServicing
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class StorageServiceTest {
    @Test
    public fun `image upload requests temporary credentials and creates prefixed object key`() = runTest {
        val api = FakeApiService()
        val manager = FakeStorageManager()
        val service = StorageService(api, manager)

        val result = service.uploadImage(
            data = byteArrayOf(1, 2, 3),
            fileName = "hello world.jpg",
            contentType = "image/jpeg",
            progress = null,
        )

        assertEquals(listOf("/cos/sts"), api.getPaths)
        assertTrue(manager.objectKey.startsWith("users/test/"))
        assertTrue(manager.objectKey.endsWith("_hello_world.jpg"))
        assertEquals("image/jpeg", manager.contentType)
        assertEquals("test-bucket-123", manager.configuration?.bucket)
        assertEquals(manager.objectKey, result.objectKey)
        assertFalse(result.url.isBlank())
    }

    @Test
    public fun `safe image upload returns checked URL`() = runTest {
        val api = FakeApiService()
        val manager = FakeStorageManager()
        val service = StorageService(api, manager)

        val result = service.uploadImageWithSafetyCheck(
            data = byteArrayOf(1),
            fileName = "avatar.png",
            contentType = "image/png",
            progress = null,
        )

        assertEquals(listOf("/cos/image/check"), api.postPaths)
        assertEquals("https://checked.example/avatar.png", result.url)
    }

    @Test
    public fun `video upload rejects image content type before requesting credentials`() {
        val api = FakeApiService()
        val service = StorageService(api, FakeStorageManager())

        val error = assertThrows(XmaxError::class.java) {
            kotlinx.coroutines.test.runTest {
                service.uploadVideo(
                    data = byteArrayOf(1),
                    fileName = "clip.mp4",
                    contentType = "image/jpeg",
                    progress = null,
                )
            }
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertTrue(api.getPaths.isEmpty())
    }
}

private class FakeApiService : ApiServicing {
    val getPaths = mutableListOf<String>()
    val postPaths = mutableListOf<String>()

    override suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject?,
    ): JSONObject = when (method) {
        ApiMethod.GET -> {
            getPaths += path
            JSONObject()
                .put("bucket", "test-bucket-123")
                .put("region", "ap-test")
                .put("endpoint", "https://cos.ap-test.myqcloud.com")
                .put("prefix", "users/test/")
                .put(
                    "credentials",
                    JSONObject()
                        .put("accessKeyId", "temporary-id")
                        .put("secretAccessKey", "temporary-secret")
                        .put("sessionToken", "temporary-token"),
                )
        }
        ApiMethod.POST -> {
            postPaths += path
            JSONObject()
                .put("safe", true)
                .put("url", "https://checked.example/avatar.png")
        }
        ApiMethod.PUT, ApiMethod.DELETE -> error("Unexpected API method: $method")
    }
}

private class FakeStorageManager : StorageManaging {
    var objectKey: String = ""
    var contentType: String = ""
    var configuration: StorageConfiguration? = null

    override suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile {
        this.objectKey = objectKey
        this.contentType = contentType
        this.configuration = configuration
        return StoredFile(
            url = "https://storage.example/$objectKey",
            objectKey = objectKey,
            etag = "etag",
        )
    }

    override suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = DownloadedFile(destination, 0L)
}
