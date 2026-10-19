package com.cookalong.app.ai

import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.net.NoInternetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class PlanMeal(
    val recipeId: String = "",
    val title: String = "",
    val minutes: Int = 0,
    val why: String = "",
    val have: Int = 0,
    val total: Int = 0,
    val missing: List<String> = emptyList(),
    val swaps: List<String> = emptyList(),
)

@Serializable
data class DinnerPlan(
    val summary: String = "",
    val meals: List<PlanMeal> = emptyList(),
    val shoppingList: List<String> = emptyList(),
    val tip: String = "",
)

/** One tool call the agent made while planning (find_recipes, get_recipe, suggest_swap, shopping_list). */
@Serializable
data class AgentStep(val tool: String = "", val input: JsonElement? = null)

@Serializable
data class PlanResult(val plan: DinnerPlan = DinnerPlan(), val steps: List<AgentStep> = emptyList(), val model: String = "") {
    /** "find_recipes → get_recipe ×2 → suggest_swap ×3": the agent's tool calls in order, repeats folded together. */
    fun trace(): String = steps.map { it.tool }.filter { it.isNotBlank() }.fold(mutableListOf<Pair<String, Int>>()) { acc, t ->
        if (acc.isNotEmpty() && acc.last().first == t) acc[acc.lastIndex] = t to acc.last().second + 1 else acc += t to 1
        acc
    }.joinToString(" → ") { (t, n) -> if (n > 1) "$t ×$n" else t }
}

/**
 * Talks to the dinner-planner agent (backend/dinner_agent.py: a Strands agent on Amazon Nova in AWS Lambda, with
 * recipe tools over DynamoDB and a Bedrock Guardrail). A plan takes about 5 to 15 seconds.
 */
class PlannerClient(private val apiBase: String, private val device: String) {

    @Serializable private data class Request(
        val device: String,
        val ingredients: List<String>,
        val servings: Int,
        val diet: String = "",
        val maxMinutes: Int = 0,
    )

    /** @throws NoInternetException when the service can't be reached; IOException with a TV-friendly message otherwise. */
    suspend fun plan(ingredients: List<String>, servings: Int, diet: String = "", maxMinutes: Int = 0): PlanResult =
        withContext(Dispatchers.IO) {
            val body = RecipeRepository.json.encodeToString(
                Request.serializer(), Request(device, ingredients.take(30), servings.coerceIn(1, 12), diet, maxMinutes),
            )
            val (code, text) = request(body)
            parse(code, text)
        }

    private fun request(json: String): Pair<Int, String> {
        val conn = try {
            URL(apiBase.trimEnd('/') + "/plan").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("The dinner planner isn't set up on this TV.")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("accept", "application/json")
            conn.setRequestProperty("content-type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The planner took too long. Please try again.", e)
        } catch (e: IOException) {
            throw NoInternetException("No internet connection. The dinner planner needs the TV to be online.", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Turns an HTTP answer into a plan, or an IOException whose message is safe to show on the TV. */
        fun parse(code: Int, body: String): PlanResult {
            if (code != 200) {
                throw IOException(
                    when (code) {
                        429 -> "You've planned a lot of dinners this hour. Try again in a little while."
                        400 -> "The planner couldn't use those ingredients. Scan again and try once more."
                        else -> "The planner couldn't finish. Please try again."
                    },
                )
            }
            val result = try {
                RecipeRepository.json.decodeFromString(PlanResult.serializer(), body)
            } catch (e: SerializationException) {
                throw IOException("The planner sent back something unexpected. Please try again.", e)
            }
            return result.copy(plan = result.plan.copy(meals = result.plan.meals.filter { it.recipeId.isNotBlank() }))
        }
    }
}
