package ai.xmax.sdk.internal.storage

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxStorageProgressListener
import ai.xmax.sdk.internal.network.ApiServicing
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
        val provider = FakeStorageProvider()
        val service = StorageService(api, provider)

        val result = service.uploadImage(
            data = byteArrayOf(1, 2, 3),
            fileName = "hello world.jpg",
            contentType = "image/jpeg",
            progress = null,
        )

        assertEquals(listOf("/cos/sts"), api.getPaths)
        assertTrue(provider.objectKey.startsWith("users/test/"))
        assertTrue(provider.objectKey.endsWith("_hello_world.jpg"))
        assertEquals("image/jpeg", provider.contentType)
        assertEquals("test-bucket-123", provider.configuration?.bucket)
        assertEquals(provider.objectKey, result.objectKey)
        assertFalse(result.url.isBlank())
    }

    @Test
    public fun `safe image upload returns checked URL`() = runTest {
        val api = FakeApiService()
        val provider = FakeStorageProvider()
        val service = StorageService(api, provider)

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
        val service = StorageService(api, FakeStorageProvider())

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

    override suspend fun get(path: String): JSONObject {
        getPaths += path
        return JSONObject()
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

    override suspend fun post(path: String, body: JSONObject): JSONObject {
        postPaths += path
        return JSONObject()
            .put("safe", true)
            .put("url", "https://checked.example/avatar.png")
    }
}

private class FakeStorageProvider : StorageProviding {
    var objectKey: String = ""
    var contentType: String = ""
    var configuration: StorageConfiguration? = null

    override suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: XmaxStorageProgressListener?,
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
        progress: XmaxStorageProgressListener?,
    ): DownloadedFile = DownloadedFile(destination, 0L)
}
