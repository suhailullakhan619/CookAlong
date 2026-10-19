package com.cookalong.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Recipe pictures: bundled ones from `assets/`, online ones over https (the CookAlong GitHub Pages site).
 *
 * Two cache levels: decoded bitmaps in memory (LRU, sized by bytes) and downloaded files on disk
 * (`cacheDir/recipe-images`, trimmed to [DISK_BYTES]), so each photo is downloaded once and still shows offline.
 * Plain HttpURLConnection + BitmapFactory: nothing here needs Google Play services, which Fire OS doesn't have.
 */
internal object RecipeImages {
    private const val DISK_BYTES = 48L * 1024 * 1024
    private const val MAX_DOWNLOAD = 4 * 1024 * 1024
    private const val RETRY_AFTER_MS = 60_000L

    private val memory = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceIn(8 * 1024, 64 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount / 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(4)
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun isRemote(path: String) = path.startsWith("https://") || path.startsWith("http://")

    fun cached(path: String): ImageBitmap? = memory.get(path)

    /** Lets a picture that failed earlier (typically offline) be requested again. */
    fun forgetFailure(path: String) { failedAt.remove(path) }

    /** True for a download that failed in the last minute: show the fallback straight away instead of a shimmer. */
    fun failedRecently(path: String) = failedAt[path]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true

    /**
     * The decoded picture, or null if it is missing, unreadable or (for a URL) can't be downloaded right now.
     * Concurrent requests for the same picture share one load; a load keeps going if the caller leaves the screen,
     * so the picture is ready when the card scrolls back into view.
     * While [online] is false a URL is only read from the disk cache: no request is made and nothing is recorded as
     * failed, so the picture loads normally once the network is back.
     */
    suspend fun load(context: Context, path: String, online: Boolean = true): ImageBitmap? {
        memory.get(path)?.let { return it }
        val app = context.applicationContext
        if (!online && isRemote(path)) return withContext(Dispatchers.IO) { runCatching { diskCached(app, path) }.getOrNull() }
        val job = synchronized(inFlight) {
            inFlight.getOrPut(path) {
                scope.async {
                    val bmp = runCatching { if (isRemote(path)) remote(app, path) else asset(app, path) }.getOrNull()
                    if (bmp != null) memory.put(path, bmp).also { failedAt.remove(path) } else failedAt[path] = System.currentTimeMillis()
                    synchronized(inFlight) { inFlight.remove(path) }
                    bmp
                }
            }
        }
        return job.await()
    }

    private fun asset(context: Context, path: String): ImageBitmap? =
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()

    /** The downloaded copy of [url], if there is a readable one. */
    private fun diskCached(context: Context, url: String): ImageBitmap? {
        val file = File(File(context.cacheDir, "recipe-images"), sha1(url))
        if (!file.isFile) return null
        decode(file.readBytes())?.let { file.setLastModified(System.currentTimeMillis()); memory.put(url, it); return it }
        file.delete() // corrupt: download again when online
        return null
    }

    private suspend fun remote(context: Context, url: String): ImageBitmap? {
        val dir = File(context.cacheDir, "recipe-images").apply { mkdirs() }
        val file = File(dir, sha1(url))
        diskCached(context, url)?.let { return it }
        val bytes = downloads.withPermit { download(url) } ?: return null
        val bmp = decode(bytes) ?: return null // an error page or a broken file is never cached
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) tmp.delete()
        trim(dir)
        return bmp
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200 || conn.contentLength > MAX_DOWNLOAD) return null
            val out = ByteArrayOutputStream(maxOf(conn.contentLength, 32 * 1024))
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Decodes at most ~1280 px wide (the photos are 640×360; this only guards against a huge file). */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    /** Least recently used files go first once the folder is over its budget. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
