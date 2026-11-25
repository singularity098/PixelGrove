package app.pixelg.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

class ApiException(val status: Int, message: String) : Exception(message)

/** The engine's JSON API, called in-process (no HTTP). */
object Api {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun raw(method: String, uri: String, body: JsonElement? = null): String =
        withContext(Dispatchers.IO) {
            val response = Native.request(method, "/api$uri", body?.toString()?.toByteArray())
            val status = String(response, 0, 3).toIntOrNull() ?: 500
            val text = String(response, 3, response.size - 3)
            if (status >= 400) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(status, message ?: "Something went wrong ($status).")
            }
            text
        }

    suspend inline fun <reified T> get(uri: String): T = json.decodeFromString(raw("GET", uri))

    suspend inline fun <reified T> send(method: String, uri: String, body: JsonElement? = null): T {
        val text = raw(method, uri, body)
        return json.decodeFromString(text.ifEmpty { "{}" })
    }

    suspend fun call(method: String, uri: String, body: JsonElement? = null) {
        raw(method, uri, body)
    }

    fun q(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                null -> {}
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                else -> error("unsupported value for $key")
            }
        }
    }
}
