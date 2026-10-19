package com.cookalong.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface CatalogResult {
    data class Loaded(val recipes: List<Recipe>, val config: AppConfig, val skipped: Int) : CatalogResult
    data class Failed(val message: String) : CatalogResult
}

/** Reads the bundled `assets/recipes.json` and `assets/config.json`. No network, no backend. */
class RecipeRepository(private val context: Context) {

    suspend fun load(): CatalogResult = withContext(Dispatchers.IO) {
        val config = runCatching { parseConfig(readAsset(CONFIG_FILE)) }.getOrDefault(AppConfig())
        val raw = runCatching { readAsset(RECIPES_FILE) }.getOrElse {
            return@withContext CatalogResult.Failed("The recipe book is missing from this install.")
        }
        runCatching { parseCatalog(raw) }
            .map { (recipes, skipped) -> CatalogResult.Loaded(recipes, config, skipped) }
            .getOrElse { CatalogResult.Failed("The recipe book couldn't be read. Try reinstalling CookAlong.") }
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        const val RECIPES_FILE = "recipes.json"
        const val CONFIG_FILE = "config.json"

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

        fun parseConfig(text: String): AppConfig = json.decodeFromString(AppConfig.serializer(), text)

        /**
         * Parses and sanity-checks the catalog. Recipes without a title or steps, or with a duplicate id, are
         * skipped (counted in the second value) rather than crashing the app.
         */
        fun parseCatalog(text: String): Pair<List<Recipe>, Int> {
            val all = json.decodeFromString(RecipeCatalog.serializer(), text).recipes
            val seen = HashSet<String>()
            val valid = all.filter { r ->
                r.id.isNotBlank() && r.title.isNotBlank() && r.steps.isNotEmpty() && seen.add(r.id)
            }.map { r -> r.copy(servings = r.servings.coerceAtLeast(1)) }
            return valid to (all.size - valid.size)
        }
    }
}
