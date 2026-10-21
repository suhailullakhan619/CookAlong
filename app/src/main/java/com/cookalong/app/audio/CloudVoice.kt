package com.cookalong.app.audio

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A natural read-aloud voice from Amazon Polly, through the backend's `POST /tts`. Each sentence is fetched once and
 * kept in the app's cache, so a recipe's steps cost nothing the second time and replay instantly. Returns null when
 * the TV is offline or the service says no; [Speaker] then uses the TV's own voice.
 */
class CloudVoice(context: Context, apiBase: String, private val deviceId: String) {
    private val endpoint = apiBase.trimEnd('/') + "/tts"
    private val dir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    /** After a failure the TV's own voice is used for a minute, so a dead network never delays every step. */
    @Volatile private var pausedUntil = 0L

    /** Blocking: call off the main thread. */
    fun audioFor(text: String): File? {
        val file = File(dir, key(text) + ".mp3")
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        if (System.currentTimeMillis() < pausedUntil || connectivity?.activeNetwork == null) return null
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("device", deviceId).put("text", text).toString().toByteArray())
            }
            if (conn.responseCode != 200 || conn.contentType?.startsWith("audio/") != true) {
                Log.w(TAG, "Cloud voice unavailable: HTTP ${conn.responseCode}")
                conn.disconnect()
                pausedUntil = System.currentTimeMillis() + PAUSE_MS
                return null
            }
            val part = File(dir, file.name + ".part")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (part.length() == 0L || !part.renameTo(file)) return null.also { part.delete() }
            trim()
            file
        } catch (e: Exception) {
            Log.w(TAG, "Cloud voice failed: $e")
            pausedUntil = System.currentTimeMillis() + PAUSE_MS
            null
        }
    }

    /** Keeps the cache under [MAX_CACHE_BYTES], dropping the least recently used sentences first. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".mp3") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun key(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        const val TAG = "CookAlongSpeech"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 6_000
        const val MAX_CACHE_BYTES = 30L * 1024 * 1024
        const val PAUSE_MS = 60_000L
    }
}
