package com.cookalong.app.alexa

import com.cookalong.app.net.NoInternetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A command the Cook Along Alexa skill queued for this TV (backend/alexa_skill.py decides which fields are set). */
@Serializable
data class AlexaCommand(
    val seq: Long = 0,
    val type: String = "",
    val seconds: Int? = null,
    val timerId: String? = null,
    val step: Int? = null,
    val count: Int? = null,
    val query: String? = null,
    val maxMinutes: Int? = null,
    val question: String? = null,
    val text: String? = null,
)

/**
 * Talks to the Alexa bridge on the CookAlong backend (backend/tv_bridge.py). Fire TV apps can only get pushes
 * through Amazon Device Messaging, so the TV polls instead: [commands] about once a second while the app is on
 * screen and linked, [publish] whenever what's on screen changes so Alexa can answer questions about it.
 */
class AlexaClient(private val apiBase: String) {

    @Serializable data class PairReply(val code: String, val expiresIn: Int = 600)

    @Serializable data class CommandsReply(
        val seq: Long = 0,
        val linked: Boolean = false,
        val commands: List<AlexaCommand> = emptyList(),
    )

    @Serializable private data class DeviceBody(val device: String)
    @Serializable private data class StateBody(val device: String, val state: AlexaState)

    /** @throws IOException with a TV-friendly message. */
    suspend fun pair(device: String): PairReply = withContext(Dispatchers.IO) {
        val body = json.encodeToString(DeviceBody.serializer(), DeviceBody(device))
        val (code, text) = request("POST", "/tv/pair", body)
        if (code != 200) throw IOException("Couldn't get a code. Please try again.")
        json.decodeFromString(PairReply.serializer(), text)
    }

    /** @param after the last seq seen, or -1 on the first poll (old commands are never replayed). */
    suspend fun commands(device: String, after: Long): CommandsReply = withContext(Dispatchers.IO) {
        val (code, text) = request("GET", "/tv/commands?device=${enc(device)}&after=$after", null)
        if (code != 200) throw IOException("Alexa bridge answered $code")
        json.decodeFromString(CommandsReply.serializer(), text)
    }

    suspend fun publish(device: String, state: AlexaState) = withContext(Dispatchers.IO) {
        val (code, _) = request("POST", "/tv/state", json.encodeToString(StateBody.serializer(), StateBody(device, state)))
        if (code != 200) throw IOException("Alexa bridge answered $code")
    }

    suspend fun unlink(device: String) = withContext(Dispatchers.IO) {
        val (code, _) = request("POST", "/tv/unlink", json.encodeToString(DeviceBody.serializer(), DeviceBody(device)))
        if (code != 200) throw IOException("Couldn't disconnect Alexa. Please try again.")
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            throw NoInternetException("No internet connection. Alexa needs the TV to be online.", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    internal companion object {
        /** Defaults are encoded (step 0, readAloud false…): the skill reads every field it needs. */
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }
    }
}
