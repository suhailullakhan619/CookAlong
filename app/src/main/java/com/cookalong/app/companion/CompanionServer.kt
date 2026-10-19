package com.cookalong.app.companion

import android.os.Build
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A tiny read-only web server so a phone on the same Wi-Fi can open the shopping list by scanning the QR code.
 *
 * - LAN only: no internet, no cloud, no account. It only answers `GET /r/<recipeId>?s=<servings>` (the bundled
 *   companion page plus that recipe's scaled ingredient list) and `GET /images/<recipeId>.png`. Everything else is 404.
 * - Fallback for when no public `companionBaseUrl` is configured; the phone must be on the same Wi-Fi.
 * - Runs only while CookAlong is in the foreground and a QR code has been shown; stopped in `onStop`.
 */
class CompanionServer(
    private val pageTemplate: () -> String,
    private val findRecipe: (String) -> Recipe?,
    /** Reads a bundled asset such as `images/<id>.png`; null if missing. */
    private val readAsset: (String) -> ByteArray? = { null },
) {
    private var scope: CoroutineScope? = null
    @Volatile private var socket: ServerSocket? = null

    /** Starts (if needed) and returns the base URL phones should use, or null if the TV isn't on a network. */
    @Synchronized
    fun start(hostOverride: String?): String? {
        val host = hostOverride ?: lanAddress() ?: return null
        val s = socket ?: runCatching { ServerSocket(PREFERRED_PORT) }
            .recoverCatching { ServerSocket(0) }
            .getOrNull()
            ?.also { launchAcceptLoop(it) }
            ?: return null
        socket = s
        return if (hostOverride != null && hostOverride.contains(':')) "http://$hostOverride" else "http://$host:${s.localPort}"
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
    }

    private fun launchAcceptLoop(server: ServerSocket) {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        sc.launch {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) } }
            }
        }
    }

    private fun handle(client: Socket): Unit = client.use { c ->
        c.soTimeout = 5_000
        val reader = c.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) { // drain headers
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val response = if (parts.size >= 2 && parts[0] == "GET") route(parts[1]) else Response(405, "text/plain", "Method not allowed")
        val body = response.bytes
        val charset = if (response.contentType.startsWith("text/")) "; charset=utf-8" else ""
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
            "Content-Type: ${response.contentType}$charset\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        with(c.getOutputStream()) {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    class Response(val status: Int, val contentType: String, val bytes: ByteArray) {
        constructor(status: Int, contentType: String, text: String) : this(status, contentType, text.toByteArray(Charsets.UTF_8))
        val body: String get() = String(bytes, Charsets.UTF_8)
    }

    private val imagePath = Regex("^/images/([a-z0-9-]{1,80})\\.(png|jpg)$")

    /** Pure routing, unit-tested in CompanionServerTest. */
    fun route(target: String): Response {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        imagePath.matchEntire(path)?.let { m ->
            val ext = m.groupValues[2]
            val bytes = readAsset("images/${m.groupValues[1]}.$ext") ?: return notFound()
            return Response(200, if (ext == "png") "image/png" else "image/jpeg", bytes)
        }
        if (!path.startsWith("/r/")) return notFound()
        val id = runCatching { URLDecoder.decode(path.removePrefix("/r/"), "UTF-8") }.getOrNull() ?: return notFound()
        val recipe = findRecipe(id) ?: return notFound()
        val servings = query.split('&').firstOrNull { it.startsWith("s=") }?.removePrefix("s=")?.toIntOrNull()
            ?.coerceIn(1, 99) ?: recipe.servings
        val data = buildJsonObject {
            put("r", recipe.id)
            // online recipes' photos live on the public site (…/images/<id>.jpg), bundled ones are served from here
            put("img", recipe.image?.takeIf { it.startsWith("https://") }?.substringBeforeLast('/')?.plus('/') ?: "/images/")
            recipe.imageCredit?.let { put("credit", it) }
            put("t", recipe.title)
            put("s", servings)
            put("i", JsonArray(ShoppingList.lines(recipe, servings).map(::JsonPrimitive)))
        }.toString().replace("<", "\\u003c") // can't close the <script> early
        val page = pageTemplate().replace(DATA_MARKER, "<script>window.COOKALONG_DATA=$data;</script>")
        return Response(200, "text/html", page)
    }

    private fun notFound() = Response(
        404, "text/html",
        "<!doctype html><meta name=viewport content='width=device-width'><body style='font:18px system-ui;padding:24px'>" +
            "This shopping list isn't available. Open CookAlong on your TV and scan the QR code again.",
    )

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    companion object {
        const val PREFERRED_PORT = 8765
        const val DATA_MARKER = "<!--COOKALONG_DATA-->"

        /** First private IPv4 address on an active interface (Wi-Fi or Ethernet). No permission needed. */
        fun lanAddress(): String? {
            val address = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull() ?: return null
            // The Android emulator's 10.0.2.x network isn't reachable from a phone; fall back to the text QR there
            // unless a developer passes a host override (see README > Testing the QR on an emulator).
            if (isEmulator() && address.startsWith("10.0.2.")) return null
            return address
        }

        private fun isEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")
    }
}
