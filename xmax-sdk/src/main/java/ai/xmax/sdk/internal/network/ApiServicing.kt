package ai.xmax.sdk.internal.network

import org.json.JSONObject

internal interface ApiServicing {
    suspend fun get(path: String): JSONObject

    suspend fun post(path: String, body: JSONObject): JSONObject
}
