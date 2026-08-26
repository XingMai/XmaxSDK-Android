package ai.xmax.sdk.internal.network

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class ApiService(
    private val apiKey: String,
    private val baseUrl: String = API_BASE_URL,
) : ApiServicing {
    override suspend fun get(path: String): JSONObject = request("GET", path, null)

    override suspend fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw XmaxError(XmaxErrorCode.INVALID_API_KEY, "API key cannot be empty")
            }

            val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = API_TIMEOUT_MS
                readTimeout = API_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Key", apiKey)
                if (body != null) {
                    doOutput = true
                }
            }

            try {
                if (body != null) {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body.toString())
                    }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                parseEnvelope(status, text)
            } catch (error: XmaxError) {
                throw error
            } catch (error: IOException) {
                throw XmaxError(
                    code = XmaxErrorCode.NETWORK_ERROR,
                    message = "HTTP request failed: ${error.message ?: error.javaClass.simpleName}",
                    cause = error,
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun parseEnvelope(status: Int, text: String): JSONObject {
        val envelope = try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid JSON",
                httpStatus = status,
                cause = error,
            )
        }

        if (status in 200..299 && envelope.optBoolean("success") &&
            envelope.has("data") && !envelope.isNull("data")
        ) {
            return envelope.optJSONObject("data") ?: throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid data",
                httpStatus = status,
            )
        }

        throw XmaxError(
            code = XmaxErrorCode.API_ERROR,
            message = envelope.optString("message").takeIf { it.isNotBlank() }
                ?: "Xmax API request failed",
            apiCode = envelope.optInt("code").takeIf { envelope.has("code") },
            httpStatus = status,
        )
    }

    private companion object {
        const val API_BASE_URL = "https://cloud.xmax.22duck.cn/open/api/v1"
        const val API_TIMEOUT_MS = 15_000
    }
}
