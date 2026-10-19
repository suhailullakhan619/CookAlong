package com.cookalong.app

import com.cookalong.app.alexa.AlexaClient
import com.cookalong.app.alexa.AlexaCommand
import com.cookalong.app.alexa.AlexaState
import com.cookalong.app.data.Ingredient
import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeSearch
import com.cookalong.app.data.Speech
import com.cookalong.app.data.Step
import com.cookalong.app.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlexaBridgeTest {

    private val dal = Recipe(
        id = "dal-tadka", title = "Dal Tadka", servings = 4, prepTime = 10, cookTime = 30,
        categories = listOf("indian"), dietaryTags = listOf("vegetarian"),
        ingredients = listOf(
            Ingredient("dal", "toor dal", 200.0, "g"),
            Ingredient("ghee", "ghee", 2.0, "tbsp"),
            Ingredient("cumin", "cumin seeds", 1.0, "tsp"),
            Ingredient("salt", "salt", null, "to taste"),
        ),
        steps = listOf(
            Step("s1", 1, "Rinse the dal.", listOf("dal")),
            Step("s2", 2, "Boil at 100 °C.", suggestedTimerSeconds = 1200, timerLabel = "Dal"),
        ),
    )
    private val paneer = Recipe(
        id = "paneer-butter-masala", title = "Paneer Butter Masala", prepTime = 15, cookTime = 25,
        categories = listOf("indian"), ingredients = listOf(Ingredient("p", "paneer", 250.0, "g")),
        steps = listOf(Step("s1", 1, "Cook.")),
    )
    private val salad = Recipe(
        id = "greek-salad", title = "Greek Salad", prepTime = 10, cookTime = 0,
        ingredients = listOf(Ingredient("t", "tomatoes", 3.0, ""), Ingredient("f", "feta", 100.0, "g")),
        steps = listOf(Step("s1", 1, "Chop.")),
    )

    @Test fun ingredientsAreReadWithUnitNames() {
        assertEquals("400 grams spaghetti", Speech.ingredient("400 g spaghetti"))
        assertEquals("half a teaspoon salt", Speech.ingredient("½ tsp salt"))
        assertEquals("1 and a half tablespoons olive oil", Speech.ingredient("1½ tbsp olive oil"))
        assertEquals("1 tablespoon ghee", Speech.ingredient("1 tbsp ghee"))
        assertEquals("3 cloves garlic", Speech.ingredient("3 cloves garlic"))
        assertEquals("salt, to taste", Speech.ingredient("salt, to taste"))
    }

    @Test fun stateIsScaledAndSpoken() {
        val timers = listOf(KitchenTimer("t1", "Dal", 1200, TimerStatus.RUNNING, 0, endsAtEpochMs = 61_000))
        val s = AlexaState.of("cooking", dal, servings = 8, minServings = 1, maxServings = 12, stepIndex = 1,
            timers = timers, tvSpeaks = true, nowMs = 1_000)
        assertEquals(listOf("400 grams toor dal", "4 tablespoons ghee", "2 teaspoons cumin seeds", "salt, to taste"), s.ingredients)
        assertEquals("Boil at 100 degrees Celsius.", s.steps[1])
        assertEquals(1200, s.stepTimerSeconds)
        assertEquals(60_000L, s.timers.single().remainingMs)
        assertEquals("RUNNING", s.timers.single().status)
    }

    @Test fun stepIngredientsComeFromTheStep() {
        val s = AlexaState.of("cooking", dal, 4, 1, 12, 0, emptyList(), false, 0)
        assertEquals(listOf("200 grams toor dal"), s.stepIngredients)
    }

    @Test fun stateJsonKeepsDefaultsTheSkillReads() {
        val s = AlexaState.of("cooking", dal, 4, 1, 12, 0, emptyList(), false, 0)
        val text = AlexaClient.json.encodeToString(AlexaState.serializer(), s)
        // step 0 and readAloud false are default values, but the skill reads them: they must be sent
        assertTrue(text, "\"step\":0" in text)
        assertTrue(text, "\"readAloud\":false" in text)
    }

    @Test fun commandsIgnoreUnknownFields() {
        val cmd = AlexaClient.json.decodeFromString(AlexaCommand.serializer(),
            """{"seq":7,"type":"timer","seconds":300,"at":1760000000000,"future":"x"}""")
        assertEquals(AlexaCommand(seq = 7, type = "timer", seconds = 300), cmd)
    }

    @Test fun searchNeedsEveryWordAndRanksTitlesFirst() {
        val all = listOf(dal, paneer, salad)
        assertEquals(listOf(paneer), RecipeSearch.find(all, "paneer recipes"))
        assertEquals(listOf(dal, paneer), RecipeSearch.find(all, "indian").sortedBy { it.id })
        assertEquals(listOf(salad), RecipeSearch.find(all, "tomato"))
        assertTrue(RecipeSearch.find(all, "paneer tomato").isEmpty())
    }

    @Test fun quickSearchFiltersByTime() {
        assertEquals(listOf(salad), RecipeSearch.find(listOf(dal, paneer, salad), "", maxMinutes = 20))
    }

    @Test fun exactTitleOpensDirectly() {
        val all = listOf(dal, paneer, salad)
        assertEquals(dal, RecipeSearch.exactTitle(all, "dal tadka"))
        assertEquals(dal, RecipeSearch.exactTitle(all, "the dal tadka recipe"))
        assertNull(RecipeSearch.exactTitle(all, "dal"))
    }
}
