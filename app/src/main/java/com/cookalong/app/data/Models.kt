package com.cookalong.app.data

import kotlinx.serialization.Serializable

@Serializable
data class RecipeCatalog(val recipes: List<Recipe> = emptyList())

@Serializable
data class Recipe(
    val id: String,
    val title: String,
    val description: String = "",
    /** Path inside `assets/`, e.g. `images/foo.png`. Missing or broken images fall back to generated art. */
    val image: String? = null,
    /** Attribution shown with the photo, e.g. "Jane Doe · CC BY-SA 4.0 · Wikimedia Commons". */
    val imageCredit: String? = null,
    /** Where the recipe comes from: "bundled", "based-cooking", "wikibooks", "howtocook", "public-domain". */
    val source: String? = null,
    /** License of the recipe text, e.g. "CC BY-SA 4.0" or "Public domain". */
    val license: String? = null,
    /** Link to the original recipe. */
    val sourceUrl: String? = null,
    /** Attribution shown with the recipe, e.g. "Wikibooks Cookbook contributors". */
    val credit: String? = null,
    /** Minutes. */
    val prepTime: Int = 0,
    /** Minutes. */
    val cookTime: Int = 0,
    /** Base servings the ingredient amounts are written for. */
    val servings: Int = 1,
    val dietaryTags: List<String> = emptyList(),
    /** Cuisine / meal type for home rows, e.g. "indian", "breakfast", "snack", "sweet". Not shown as chips. */
    val categories: List<String> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<Step> = emptyList(),
) {
    val totalMinutes: Int get() = prepTime + cookTime
    /** Downloaded from the online catalog rather than bundled with the app. */
    val isOnline: Boolean get() = source != null && source != "bundled"
    val orderedSteps: List<Step> get() = steps.sortedBy { it.order }
    fun ingredient(id: String): Ingredient? = ingredients.firstOrNull { it.id == id }
}

@Serializable
data class Ingredient(
    val id: String,
    val name: String,
    /** Null means "to taste" / unquantified. */
    val amount: Double? = null,
    val unit: String = "",
    val substitutionNotes: String? = null,
)

@Serializable
data class Step(
    val id: String,
    val order: Int,
    val instructionText: String,
    val relatedIngredientIds: List<String> = emptyList(),
    val suggestedTimerSeconds: Int? = null,
    /** Short label for a timer started from this step, e.g. "Pasta". */
    val timerLabel: String? = null,
    /**
     * Optional animation: boil, stir, chop, fry, bake, mix, microwave, pour, rest or serve.
     * Leave it out and one is picked from the instruction text.
     */
    val animation: String? = null,
)

@Serializable
data class AppConfig(
    /** "dark", "light" or "system". The user's in-app choice overrides this. */
    val theme: String = "dark",
    /** Optional hosted copy of `companion/index.html`. Blank = QR carries the list as plain text. */
    val companionBaseUrl: String = "",
    /** Online recipe catalog (GET /recipes on the backend). Blank = bundled recipes only. */
    val recipesApiUrl: String = "",
    /** Fridge-scan API (backend/deploy.sh prints it). Blank hides the Fridge scan feature. */
    val scanApiUrl: String = "",
    /** Phone camera page opened from the fridge-scan QR code. */
    val scanPageUrl: String = "",
    /** Alexa bridge (POST /tv/pair, GET /tv/commands… on the backend). Blank hides "Connect Alexa". */
    val alexaApiUrl: String = "",
    val defaultCustomTimerMinutes: Int = 5,
    val maxConcurrentTimers: Int = 4,
    val minServings: Int = 1,
    val maxServings: Int = 12,
    val rows: List<RowRule> = listOf(RowRule(title = "All recipes")),
)

/** A home-screen shelf. Filters are ANDed; a row with no filters shows everything. */
@Serializable
data class RowRule(
    val title: String,
    val maxTotalMinutes: Int? = null,
    val anyTags: List<String> = emptyList(),
    val anyCategories: List<String> = emptyList(),
    /** Recipe sources, e.g. ["wikibooks"]; "online" means any recipe downloaded from the backend. */
    val anySources: List<String> = emptyList(),
) {
    fun matches(r: Recipe): Boolean =
        (maxTotalMinutes == null || r.totalMinutes <= maxTotalMinutes) &&
            (anyTags.isEmpty() || r.dietaryTags.any { it in anyTags }) &&
            (anyCategories.isEmpty() || r.categories.any { it in anyCategories }) &&
            (anySources.isEmpty() || anySources.any { it == r.source || (it == "online" && r.isOnline) })
}

enum class TimerStatus { RUNNING, PAUSED, COMPLETED }

/**
 * A kitchen timer. While running, the source of truth is [endsAtEpochMs] (wall clock), so a timer keeps
 * counting correctly across backgrounding, process death and relaunch.
 */
@Serializable
data class KitchenTimer(
    val id: String,
    val label: String,
    val durationSeconds: Int,
    val status: TimerStatus,
    /** Valid when PAUSED/COMPLETED; recomputed from [endsAtEpochMs] when RUNNING. */
    val remainingMs: Long,
    val endsAtEpochMs: Long = 0,
    val stepId: String? = null,
) {
    fun remainingAt(nowMs: Long): Long = when (status) {
        TimerStatus.RUNNING -> (endsAtEpochMs - nowMs).coerceAtLeast(0)
        TimerStatus.PAUSED -> remainingMs
        TimerStatus.COMPLETED -> 0
    }
}

/** Everything needed to drop the cook back where they were. No personal data. */
@Serializable
data class CookingSession(
    val recipeId: String,
    val stepIndex: Int,
    val servings: Int,
    val timers: List<KitchenTimer> = emptyList(),
)
