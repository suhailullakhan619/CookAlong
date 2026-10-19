package com.cookalong.app.ai

import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.net.NoInternetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class DetectedItem(val name: String, val confidence: String = "medium")

/**
 * Talks to the fridge-scan backend (backend/lambda_function.py: AWS Lambda -> Amazon Nova on Bedrock).
 * The TV only creates a pairing code and polls for the result; the phone uploads the photo.
 */
class FridgeScanClient(private val apiBase: String) {

    sealed interface Poll {
        data object Waiting : Poll
        /** The phone has sent a photo and the AI is looking at it. */
        data object Analyzing : Poll
        data class Done(val items: List<DetectedItem>, val note: String) : Poll
        data class Failed(val message: String) : Poll
        data object Expired : Poll
    }

    @Serializable private data class SessionReply(val code: String, val expiresIn: Int = 900)
    @Serializable private data class ScanReply(
        val status: String,
        val ingredients: List<DetectedItem> = emptyList(),
        val note: String = "",
        val message: String = "",
    )

    /** @throws NoInternetException when the service can't be reached; IOException with a TV-friendly message otherwise. */
    suspend fun startSession(): String = withContext(Dispatchers.IO) {
        val (code, body) = request("POST", "/session", "{}")
        if (code != 200) throw IOException("Couldn't start a fridge scan. Please try again.")
        RecipeRepository.json.decodeFromString(SessionReply.serializer(), body).code
    }

    suspend fun poll(code: String): Poll = withContext(Dispatchers.IO) {
        val (status, body) = request("GET", "/scan?code=" + URLEncoder.encode(code, "UTF-8"), null)
        if (status == 404) return@withContext Poll.Expired
        if (status != 200) return@withContext Poll.Waiting // transient; keep polling
        val reply = RecipeRepository.json.decodeFromString(ScanReply.serializer(), body)
        when (reply.status) {
            "done" -> Poll.Done(reply.ingredients, reply.note)
            "error" -> Poll.Failed(reply.message.ifBlank { "The scan didn't work. Try another photo." })
            "expired" -> Poll.Expired
            "scanning" -> Poll.Analyzing
            else -> Poll.Waiting
        }
    }

    private fun request(method: String, path: String, json: String?): Pair<Int, String> {
        val conn = try {
            URL(apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Fridge scan isn't set up on this TV.")
        }
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("accept", "application/json")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            throw NoInternetException("No internet connection. Fridge scan needs the TV to be online.", e)
        } finally {
            conn.disconnect()
        }
    }
}
