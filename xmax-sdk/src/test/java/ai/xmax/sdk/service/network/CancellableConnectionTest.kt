package ai.xmax.sdk.service.network

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellableConnectionTest {
    @Test fun `cancel disconnects blocked read and joins worker cleanup`() = runBlocking {
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val workerMayFinish = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://example.invalid")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown() }
            override fun getResponseCode() = 200
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(disconnected.await(5, TimeUnit.SECONDS))
                    return -1
                }
                override fun close() { check(workerMayFinish.await(5, TimeUnit.SECONDS)) }
            }
        }
        val transport = UrlConnectionApiTransport(connectionFactory = { connection })
        val request = ApiHttpRequest(ApiMethod.GET, connection.url, emptyMap(), null, 5000, 5000)
        val job = async(Dispatchers.Default) { transport.execute(request) }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(disconnected.await(1, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
        } finally { workerMayFinish.countDown() }
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
