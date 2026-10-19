package com.cookalong.app

import com.cookalong.app.data.Ingredient
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeSearch
import com.cookalong.app.data.RemoteCatalog
import com.cookalong.app.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeSearchTest {

    private fun recipe(id: String, title: String, vararg ingredients: String, minutes: Int = 20) = Recipe(
        id = id, title = title, cookTime = minutes,
        ingredients = ingredients.mapIndexed { i, n -> Ingredient("i$i", n) },
        steps = listOf(Step("s", 1, "Cook.")),
    )

    private val recipes = listOf(
        recipe("pepper-rice", "Pepper Rice", "green bell pepper", "rice"),
        recipe("pepper-chicken", "Black Pepper Chicken", "chicken", "black pepper"),
        recipe("moussaka", "Moussaka", "aubergine", "lamb"),
        recipe("raita", "Raita", "plain yogurt", "cucumber"),
        recipe("dal", "Dal Tadka", "toor dal", "ghee"),
    )

    @Test fun indianAndBritishNamesFindTheSameRecipes() {
        assertEquals(listOf("pepper-rice"), RecipeSearch.find(recipes, "capsicum recipes").map { it.id })
        assertEquals(listOf("moussaka"), RecipeSearch.find(recipes, "brinjal").map { it.id })
        assertEquals(listOf("moussaka"), RecipeSearch.find(recipes, "eggplant dishes").map { it.id })
        assertEquals(listOf("raita"), RecipeSearch.find(recipes, "something with curd").map { it.id })
    }

    @Test fun aPartOfASynonymIsNotEnough() {
        // "capsicum" must not match black pepper just because both contain "pepper"
        assertFalse(RecipeSearch.find(recipes, "capsicum").any { it.id == "pepper-chicken" })
    }

    @Test fun plainSearchStillWorks() {
        assertEquals(listOf("pepper-chicken"), RecipeSearch.find(recipes, "chicken").map { it.id })
        assertEquals("dal", RecipeSearch.exactTitle(recipes, "dal tadka please")?.id)
        assertTrue(RecipeSearch.find(recipes, "").isEmpty())
    }
}
