package com.cookalong.app

import com.cookalong.app.ai.PlanMeal
import com.cookalong.app.ai.PlannerClient
import com.cookalong.app.ui.screens.usefulSwaps
import com.cookalong.app.alexa.AlexaCommand
import com.cookalong.app.alexa.AlexaState
import com.cookalong.app.data.RecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PlannerClientTest {

    private val reply = """
        {"plan":{"summary":"Tonight, enjoy Paneer Butter Masala.","meals":[
          {"recipeId":"paneer-butter-masala","title":"Paneer Butter Masala","minutes":35,"why":"Uses your paneer.",
           "have":3,"total":10,"missing":["butter","garlic"],"swaps":["butter: ghee"],"extra":"ignored"},
          {"recipeId":"","title":"No id"}],
         "shoppingList":["2 tbsp butter","3 clove garlic"],"tip":"Add cream."},
         "steps":[{"tool":"find_recipes","input":{"ingredients":["paneer"],"max_minutes":0}},
                  {"tool":"get_recipe","input":{"recipe_id":"a"}},{"tool":"get_recipe","input":{"recipe_id":"b"}},
                  {"tool":"suggest_swap","input":{"missing_ingredient":"butter"}}],
         "model":"apac.amazon.nova-pro-v1:0"}
    """.trimIndent()

    @Test fun `parses a plan and drops meals without a recipe id`() {
        val r = PlannerClient.parse(200, reply)
        assertEquals("Tonight, enjoy Paneer Butter Masala.", r.plan.summary)
        assertEquals(1, r.plan.meals.size)
        val m = r.plan.meals.single()
        assertEquals("paneer-butter-masala", m.recipeId)
        assertEquals(35, m.minutes)
        assertEquals(3, m.have)
        assertEquals(listOf("butter", "garlic"), m.missing)
        assertEquals(listOf("butter: ghee"), m.swaps)
        assertEquals(2, r.plan.shoppingList.size)
        assertEquals("Add cream.", r.plan.tip)
        assertEquals("apac.amazon.nova-pro-v1:0", r.model)
    }

    @Test fun `trace folds repeated tool calls`() {
        assertEquals("find_recipes → get_recipe ×2 → suggest_swap", PlannerClient.parse(200, reply).trace())
    }

    @Test fun `an empty plan parses with no meals`() {
        val r = PlannerClient.parse(200, """{"plan":{"summary":"","meals":[],"shoppingList":[],"tip":""},"steps":[]}""")
        assertTrue(r.plan.meals.isEmpty())
        assertEquals("", r.trace())
    }

    @Test fun `missing fields fall back to defaults`() {
        val r = PlannerClient.parse(200, """{"plan":{"meals":[{"recipeId":"x"}]}}""")
        assertEquals("x", r.plan.meals.single().recipeId)
        assertEquals(0, r.plan.meals.single().total)
        assertTrue(r.plan.shoppingList.isEmpty())
    }

    @Test fun `errors become friendly messages`() {
        fun message(code: Int, body: String) = try {
            PlannerClient.parse(code, body); fail("expected IOException"); ""
        } catch (e: IOException) { e.message.orEmpty() }
        assertTrue(message(429, """{"error":"That's a lot of plans"}""").contains("this hour"))
        assertTrue(message(502, """{"error":"The planner couldn't finish."}""").contains("couldn't finish"))
        assertTrue(message(400, """{"error":"Bad device id"}""").contains("ingredients"))
        assertTrue(message(500, "<html>boom</html>").contains("couldn't finish"))
    }

    @Test fun `garbage with a 200 is a friendly error not a crash`() {
        try {
            PlannerClient.parse(200, "not json")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unexpected"))
        }
    }

    @Test fun `alexa plan command carries servings`() {
        val cmd = RecipeRepository.json.decodeFromString(
            AlexaCommand.serializer(), """{"seq":7,"type":"plan","servings":4}""",
        )
        assertEquals("plan", cmd.type)
        assertEquals(4, cmd.servings)
    }

    @Test fun `alexa state publishes the scanned ingredients`() {
        val state = AlexaState.of(
            "plan", null, 2, 1, 12, 0, emptyList(), tvSpeaks = false, nowMs = 0L, scanIngredients = listOf("paneer", "onion"),
        )
        assertEquals(listOf("paneer", "onion"), state.scanIngredients)
        val json = RecipeRepository.json.encodeToString(AlexaState.serializer(), state)
        assertTrue(json.contains("\"scanIngredients\":[\"paneer\",\"onion\"]"))
        // empty by default, so a TV that never scanned says so
        assertTrue(AlexaState.of("home", null, 1, 1, 12, 0, emptyList(), false, 0L).scanIngredients.isEmpty())
    }

    @Test fun `swaps the agent could not find are hidden`() {
        val m = PlanMeal(swaps = listOf("butter: ghee", "garlic: none", "oregano: None.", "ginger: n/a", "cream"))
        assertEquals(listOf("butter: ghee", "cream"), usefulSwaps(m))
    }
}
