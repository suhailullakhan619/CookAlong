package com.cookalong.app.alexa

import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.Scaling
import com.cookalong.app.data.Speech
import kotlinx.serialization.Serializable

/**
 * What's on the TV, published for the Alexa skill (backend/alexa_skill.py) so it can answer "how much rice do I
 * need?" or "how long is left?" with exactly what the screen shows. Text is pre-formatted for speech.
 */
@Serializable
data class AlexaState(
    /** home, detail, cooking, scan, search or alexa. */
    val screen: String,
    val recipeId: String? = null,
    val title: String? = null,
    val servings: Int = 0,
    val minServings: Int = 1,
    val maxServings: Int = 12,
    val step: Int = 0,
    val stepCount: Int = 0,
    val steps: List<String> = emptyList(),
    val stepTimerSeconds: Int? = null,
    val stepTimerLabel: String? = null,
    val stepIngredients: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
    /** The TV reads steps aloud itself, so Alexa should keep its replies short. */
    val readAloud: Boolean = false,
    val timers: List<Timer> = emptyList(),
) {
    @Serializable
    data class Timer(val id: String, val label: String, val status: String, val remainingMs: Long)

    companion object {
        fun of(
            screen: String,
            recipe: Recipe?,
            servings: Int,
            minServings: Int,
            maxServings: Int,
            stepIndex: Int,
            timers: List<KitchenTimer>,
            tvSpeaks: Boolean,
            nowMs: Long,
        ): AlexaState {
            val timerInfo = timers.map { Timer(it.id, it.label, it.status.name, it.remainingAt(nowMs)) }
            if (recipe == null) return AlexaState(screen = screen, readAloud = tvSpeaks, timers = timerInfo)
            val factor = Scaling.factor(recipe.servings, servings)
            val steps = recipe.orderedSteps
            val step = steps.getOrNull(stepIndex)
            return AlexaState(
                screen = screen,
                recipeId = recipe.id,
                title = recipe.title,
                servings = servings,
                minServings = minServings,
                maxServings = maxServings,
                step = stepIndex,
                stepCount = steps.size,
                steps = steps.map { Speech.clean(it.instructionText) },
                stepTimerSeconds = step?.suggestedTimerSeconds,
                stepTimerLabel = step?.timerLabel,
                stepIngredients = step?.relatedIngredientIds.orEmpty()
                    .mapNotNull { recipe.ingredient(it) }.map { Speech.ingredient(Scaling.describe(it, factor)) },
                ingredients = recipe.ingredients.map { Speech.ingredient(Scaling.describe(it, factor)) },
                readAloud = tvSpeaks,
                timers = timerInfo,
            )
        }
    }
}
