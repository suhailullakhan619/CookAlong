package com.cookalong.app

import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.data.Speech
import com.cookalong.app.data.Step
import com.cookalong.app.data.StepAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StepActionTest {

    private fun step(text: String, animation: String? = null) = Step("s", 1, text, animation = animation)

    @Test fun detectsFromText() {
        assertEquals(StepAction.BOIL, StepAction.of(step("Bring a large pot of water to a rolling boil.")))
        assertEquals(StepAction.CHOP, StepAction.of(step("Halve the potatoes and chunk the peppers.")))
        assertEquals(StepAction.MIX, StepAction.of(step("Whisk the eggs with the turmeric.")))
        assertEquals(StepAction.FRY, StepAction.of(step("Cook until bubbles appear, then flip.")))
        assertEquals(StepAction.BAKE, StepAction.of(step("Roast until the skin is crisp.")))
        assertEquals(StepAction.MICROWAVE, StepAction.of(step("Microwave on high until risen.")))
        assertEquals(StepAction.REST, StepAction.of(step("Rest for five minutes, then serve.")))
        assertEquals(StepAction.SERVE, StepAction.of(step("Slide onto a plate and eat while hot.")))
        assertEquals(StepAction.STIR, StepAction.of(step("Add the garlic and cook for one minute.")))
    }

    @Test fun explicitAnimationWins() {
        assertEquals(StepAction.BOIL, StepAction.of(step("Add the spaghetti and stir.", animation = "Boil")))
        assertEquals(StepAction.CHOP, StepAction.of(step("Chop it.", animation = "not-a-real-one")))
    }

    @Test fun bundledAnimationNamesAreValid() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        val (recipes, _) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        recipes.flatMap { it.steps }.mapNotNull { it.animation }.forEach { name ->
            assertTrue("unknown animation '$name'", StepAction.entries.any { it.name.equals(name, ignoreCase = true) })
        }
    }

    @Test fun speechReadsNaturally() {
        assertEquals("Step 1. Heat the oven to 220 degrees Celsius or 425 degrees Fahrenheit.",
            Speech.forStep(0, "Heat the oven to 220 °C / 425 °F."))
        assertEquals("Add 1 and a half cups and half a lemon", Speech.clean("Add 1½ cups and ½ a lemon"))
    }
}
