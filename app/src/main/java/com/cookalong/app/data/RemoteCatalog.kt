package com.cookalong.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online recipes (DynamoDB `cookalong-recipes`, served by `GET /recipes` on the CookAlong backend).
 *
 * Offline-first: the TV always starts from the bundled recipes plus the last downloaded set (cached in a file),
 * then fetches only what changed since the last sync. If the network is down, nothing breaks.
 */
class RemoteCatalog(context: Context, private val apiBase: String) {

    private val cacheFile = File(context.filesDir, "remote-recipes.json")

    @Serializable
    data class Cache(val serverTime: Long = 0, val recipes: List<Recipe> = emptyList())

    @Serializable
    private data class Page(val recipes: List<Recipe> = emptyList(), val next: String? = null, val serverTime: Long = 0)

    /** The recipes from the last successful sync (empty on first run or if the cache is unreadable). */
    suspend fun cached(): Cache = withContext(Dispatchers.IO) {
        runCatching { RecipeRepository.json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrDefault(Cache())
    }

    /**
     * Downloads recipes added or changed since the cached sync and saves the merged set.
     * @return the updated cache and how many recipes were new.
     * @throws IOException when the backend can't be reached.
     */
    suspend fun sync(): Pair<Cache, Int> = withContext(Dispatchers.IO) {
        val old = cached()
        // "Changed since" can't see recipes deleted on the server, so now and then fetch everything and start over.
        val full = needsFullRefresh(old.serverTime, System.currentTimeMillis())
        val since = if (full) 0L else old.serverTime
        val fetched = mutableListOf<Recipe>()
        var next: String? = null
        var serverTime = old.serverTime
        var pages = 0
        do {
            val query = buildString {
                append("/recipes?since=").append(since)
                if (next != null) append("&next=").append(URLEncoder.encode(next, "UTF-8"))
            }
            val page = RecipeRepository.json.decodeFromString(Page.serializer(), get(query))
            fetched += page.recipes
            if (pages == 0) serverTime = page.serverTime // the time the sync started, for the next "since"
            next = page.next
            pages++
        } while (next != null && pages < 50)
        val (merged, mergedNew) = merge(if (full && fetched.isNotEmpty()) emptyList() else old.recipes, fetched)
        val oldIds = old.recipes.mapTo(HashSet()) { it.id }
        val added = if (full) merged.count { it.id !in oldIds } else mergedNew // a full refresh isn't "477 new recipes"
        val cache = Cache(serverTime, merged)
        cacheFile.writeText(RecipeRepository.json.encodeToString(Cache.serializer(), cache))
        cache to added
    }

    private fun get(path: String): String {
        val conn = URL(apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("accept", "application/json")
            if (conn.responseCode != 200) throw IOException("Recipe sync failed (${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** How long the cache is trusted for incremental syncs before everything is downloaded again. */
        const val FULL_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

        fun needsFullRefresh(lastServerTime: Long, nowMs: Long) = lastServerTime <= 0 || nowMs - lastServerTime > FULL_REFRESH_MS

        /** Replaces changed recipes by id, appends new ones, drops ones that don't look like real recipes. */
        fun merge(old: List<Recipe>, fetched: List<Recipe>): Pair<List<Recipe>, Int> {
            val byId = LinkedHashMap<String, Recipe>()
            old.forEach { byId[it.id] = it }
            var added = 0
            fetched.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.steps.isNotEmpty() }.forEach { r ->
                if (r.id !in byId) added++
                byId[r.id] = r.copy(servings = r.servings.coerceAtLeast(1))
            }
            return byId.values.toList() to added
        }

        /**
         * What the app shows: bundled recipes first (they have local photos), then online ones that aren't
         * already bundled.
         */
        fun combine(bundled: List<Recipe>, online: List<Recipe>): List<Recipe> {
            val ids = bundled.mapTo(HashSet()) { it.id }
            val titles = bundled.mapTo(HashSet()) { it.title.lowercase() }
            return bundled + online.filter { it.id !in ids && it.title.lowercase() !in titles }
        }
    }
}
