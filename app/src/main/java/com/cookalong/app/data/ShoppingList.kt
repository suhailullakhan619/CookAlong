package com.cookalong.app.data

import java.net.URLEncoder
import java.text.Normalizer

/** What the shopping-list QR encodes, and the one-line instruction shown next to it. */
data class ShoppingQr(val payload: String, val mode: Mode) {
    enum class Mode {
        /** A link to the page served by this TV over the local network (the default). */
        LAN,
        /** A link to a hosted copy of the companion page (`companionBaseUrl` in config.json). */
        HOSTED,
        /** No network: the list itself as plain text. */
        TEXT,
    }
}

object ShoppingList {

    fun lines(recipe: Recipe, servings: Int): List<String> {
        val f = Scaling.factor(recipe.servings, servings)
        return recipe.ingredients.map { Scaling.describe(it, f) }
    }

    /**
     * Picks the most phone-friendly QR:
     * 1. `companionBaseUrl` configured → public hosted page, scannable from any network; list in the `#fragment`.
     * 2. TV has a LAN address → short link to [com.cookalong.app.companion.CompanionServer] on this TV.
     *    Opens as a normal web page on any phone on the same Wi-Fi; no internet needed.
     * 3. Otherwise → plain ASCII text, which phone cameras show as text.
     */
    fun qr(recipe: Recipe, servings: Int, companionBaseUrl: String, lanBaseUrl: String?): ShoppingQr = when {
        companionBaseUrl.isNotBlank() -> ShoppingQr(hostedUrl(recipe, servings, companionBaseUrl), ShoppingQr.Mode.HOSTED)
        lanBaseUrl != null -> ShoppingQr(lanUrl(recipe, servings, lanBaseUrl), ShoppingQr.Mode.LAN)
        else -> ShoppingQr(plainText(recipe, servings), ShoppingQr.Mode.TEXT)
    }

    /** Path served by CompanionServer: `/r/<recipeId>?s=<servings>`. Short, so the QR stays coarse and easy to scan. */
    fun lanUrl(recipe: Recipe, servings: Int, lanBaseUrl: String): String =
        lanBaseUrl.trimEnd('/') + "/r/" + enc(recipe.id) + "?s=" + servings

    /** Public page; works from any network. Everything rides in the `#fragment`, which never reaches the server. */
    fun hostedUrl(recipe: Recipe, servings: Int, companionBaseUrl: String): String =
        companionBaseUrl.trimEnd('#') +
            "#r=" + enc(recipe.id) + "&t=" + enc(recipe.title) + "&s=" + servings + "&i=" + enc(lines(recipe, servings).joinToString("\n"))

    /**
     * ASCII only (½ → 1/2), so the QR needs no character-set header, which some phone scanners mis-read.
     */
    fun plainText(recipe: Recipe, servings: Int): String = buildString {
        append("CookAlong shopping list\n")
        append(toAscii(recipe.title)).append(" (for ").append(servings).append(if (servings == 1) " serving)\n" else " servings)\n")
        lines(recipe, servings).forEach { append("- ").append(toAscii(it)).append('\n') }
    }.trimEnd()

    private val glyphs = mapOf('⅛' to "1/8", '¼' to "1/4", '⅓' to "1/3", '½' to "1/2", '⅔' to "2/3", '¾' to "3/4")

    private val replacements = mapOf('°' to " deg", 'ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", '–' to "-", '—' to "-", '’' to "'", '‘' to "'", '“' to "\"", '”' to "\"")

    /** "1½ cups purée" -> "1 1/2 cups puree". Characters with no ASCII form (e.g. CJK) are dropped. */
    fun toAscii(s: String): String {
        val out = buildString {
            s.forEachIndexed { i, ch ->
                val frac = glyphs[ch]
                when {
                    frac != null -> {
                        if (i > 0 && s[i - 1].isDigit()) append(' ')
                        append(frac)
                    }
                    ch.code < 128 -> append(ch)
                    replacements.containsKey(ch) -> append(replacements.getValue(ch))
                    else -> append(
                        // é -> e, ä -> a: decompose and keep the base letter if it's ASCII
                        Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).filter { it.code < 128 },
                    )
                }
            }
        }
        return out.replace(Regex("\\(\\s*\\)"), "").replace(Regex(" {2,}"), " ").trim()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
