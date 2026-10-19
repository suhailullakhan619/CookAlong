package com.cookalong.app

import com.cookalong.app.data.Ingredient
import com.cookalong.app.data.MatchTier
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeMatcher
import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeMatcherTest {

    private fun recipe(id: String, vararg names: String, minutes: Int = 10) = Recipe(
        id = id, title = id, servings = 2, cookTime = minutes,
        ingredients = names.mapIndexed { i, n -> Ingredient("i$i", n, 1.0, "") },
        steps = listOf(Step("s1", 1, "Cook.")),
    )

    private fun assertCovers(detected: String, ingredient: String) =
        assertTrue("\"$detected\" should count as \"$ingredient\"", RecipeMatcher.covers(detected, ingredient))

    private fun assertNotCovers(detected: String, ingredient: String) =
        assertFalse("\"$detected\" should NOT count as \"$ingredient\"", RecipeMatcher.covers(detected, ingredient))

    @Test fun pluralsVarietiesAndAlternatives() {
        val r = recipe(
            "r", "eggs, beaten", "bell peppers, chunked", "basmati rice", "butter or margarine", "tomatoes", "green chilies",
        )
        val m = RecipeMatcher.rank(listOf(r), listOf("egg", "red bell pepper", "rice", "butter", "tomato", "chili")).single()
        assertEquals(6, m.have.size)
        assertTrue(m.missing.isEmpty())
    }

    @Test fun indianBritishAndUsNamesMatch() {
        assertCovers("capsicum", "1 green bell pepper, diced")
        assertCovers("bell pepper", "capsicum, cut into squares")
        assertCovers("shimla mirch", "red bell peppers")
        assertCovers("coriander leaves", "fresh cilantro, chopped")
        assertCovers("cilantro", "chopped fresh coriander (dhania)")
        assertCovers("coriander", "cilantro leaves, chopped")
        assertCovers("curd", "plain yogurt")
        assertCovers("dahi", "Greek yoghurt")
        assertCovers("yogurt", "curds/yogurt (dahi)")
        assertCovers("brinjal", "eggplant")
        assertCovers("aubergine", "medium eggplant, cubed")
        assertCovers("eggplant", "medium-sized bainganas (eggplant / brinjal / begun)")
        assertCovers("lady finger", "okra, sliced into rounds")
        assertCovers("bhindi", "okra")
        assertCovers("okra", "ladies finger (bhindi)")
        assertCovers("maida", "all-purpose flour")
        assertCovers("plain flour", "all purpose flour")
        assertCovers("atta", "whole wheat flour")
        assertCovers("whole wheat flour", "atta (chapati flour)")
        assertCovers("chilli", "green chili, chopped")
        assertCovers("green chillies", "Indian green chiles, finely chopped")
        assertCovers("spring onion", "scallions, sliced")
        assertCovers("green onion", "spring onions")
        assertCovers("besan", "chickpea flour (besan)")
        assertCovers("gram flour", "besan")
        assertCovers("courgette", "zucchini")
        assertCovers("prawns", "jumbo shrimp, peeled and deveined")
        assertCovers("lime", "lemon juice")
        assertCovers("toor dal", "toor dal (split pigeon peas)")
        assertCovers("dal", "toor dal")
        assertCovers("red lentils", "masoor dal")
        assertCovers("chickpeas", "kabuli chana, soaked overnight")
        assertCovers("paneer", "cubes paneer")
        assertCovers("double cream", "heavy cream")
        assertCovers("fresh cream", "cream")
        assertCovers("spaghetti", "pasta")
        assertCovers("aloo", "potatoes, peeled and cubed")
        assertCovers("chicken", "boneless chicken thighs")
        assertCovers("ginger", "fresh ginger")
    }

    @Test fun differentThingsDoNotMatch() {
        assertNotCovers("milk", "coconut milk")
        assertNotCovers("coconut milk", "milk")
        assertNotCovers("onion", "spring onions")
        assertNotCovers("potato", "sweet potato")
        assertNotCovers("whole wheat flour", "flour")
        assertNotCovers("flour", "atta")
        assertNotCovers("rice", "rice flour")
        assertNotCovers("egg", "egg noodles")
        assertNotCovers("chicken", "chicken stock")
        assertNotCovers("cream", "sour cream")
        assertNotCovers("peanut butter", "butter")
        assertNotCovers("toor dal", "masoor dal")
        assertNotCovers("coriander leaves", "ground coriander")
        assertNotCovers("chili", "red chilli powder")
        assertNotCovers("tomato", "tomato paste")
        assertNotCovers("bell pepper", "black pepper")
    }

    @Test fun compositesNeedEveryPart() {
        val r = recipe("curry", "ginger-garlic paste", "onion")
        assertEquals(listOf("onion"), RecipeMatcher.rank(listOf(r), listOf("onion", "ginger")).single().have.map { it.name })
        assertEquals(2, RecipeMatcher.rank(listOf(r), listOf("onion", "ginger", "garlic")).single().have.size)
    }

    @Test fun staplesAndSpicesNeverCountEitherWay() {
        listOf(
            "salt", "Salt to taste", "olive oil", "salt and pepper", "water", "sugar", "garam masala", "turmeric powder",
            "red chilli powder", "cumin seeds", "ground coriander", "curry leaves", "dried red chilies", "baking powder",
            "chicken bouillon cube", "soy sauce", "ghee", "a little bit of salt (1/3 of a teaspoon)", "oil or ghee", "dried oregano",
            "kasuri methi (dried fenugreek leaves)", "bay leaves", "black pepper", "sunflower oil for frying",
        ).forEach { assertTrue("$it should be pantry", RecipeMatcher.isPantry(it)) }
        listOf("garlic", "paneer", "butter", "fresh basil", "lemon", "green chilli", "onion", "chicken stock", "vegetable broth").forEach {
            assertFalse("$it should not be pantry", RecipeMatcher.isPantry(it))
        }

        val r = recipe("r", "salt", "olive oil", "garam masala", "water", "sugar", "spinach")
        val m = RecipeMatcher.rank(listOf(r), listOf("spinach")).single()
        assertEquals(1, m.needed)
        assertEquals(1.0, m.score, 0.0)
        // "You have salt" is not a match.
        assertTrue(RecipeMatcher.rank(listOf(r), listOf("salt", "oil", "sugar", "garam masala")).isEmpty())
        assertEquals(listOf("spinach"), RecipeMatcher.usable(listOf("salt", "spinach", "olive oil", "ketchup")))
    }

    @Test fun tamarindIsAnIngredientEvenAsExtract() {
        assertFalse(RecipeMatcher.isPantry("tamarind extract"))
        assertCovers("tamarind", "tamarind extract")
        assertTrue(RecipeMatcher.isPantry("vanilla extract"))
    }

    @Test fun anEggAloneDoesNotMakeASoupReady() {
        val soup = recipe("Stracciatella", "eggs", "chicken broth", "parmesan")
        val ranked = RecipeMatcher.rank(listOf(soup), listOf("egg", "parmesan"))
        assertEquals(listOf("chicken broth"), ranked.single().missing.map { it.name })
    }

    @Test fun optionalExtrasNeverCountAsMissing() {
        listOf("eggs (optional)", "maple syrup (optional)", "chopped scallion, optional", "cooked rice, for serving",
            "fresh coriander leaves (as a garnish)", "tortilla chips, to serve").forEach {
            assertTrue("$it should be optional", RecipeMatcher.isOptional(it))
        }
        val r = recipe("toast", "bread", "eggs", "maple syrup (optional)", "butter, for the pan")
        assertTrue(RecipeMatcher.rank(listOf(r), listOf("bread", "egg")).single().missing.isEmpty())
    }

    @Test fun repeatedIngredientsCountOnce() {
        val r = recipe("r", "onion, chopped", "red onion, sliced", "tomato", "roma tomato")
        val m = RecipeMatcher.rank(listOf(r), listOf("onion")).single()
        assertEquals(2, m.needed)
    }

    @Test fun groupsAndRanksByHowCookable() {
        val ready = recipe("ready", "egg", "tomato", "onion", minutes = 20)
        val readyQuick = recipe("ready-quick", "egg", "tomato", "onion", minutes = 5)
        val oneShort = recipe("one-short", "egg", "tomato", "onion", "cheese")
        val almost = recipe("almost", "egg", "tomato", "lemon", "paneer")
        val halfOfTwo = recipe("half-of-two", "garlic", "cauliflower")
        val far = recipe("far", "egg", "chicken", "potato", "lemon", "paneer", "cream")
        val none = recipe("none", "chicken", "potato")
        val ranked = RecipeMatcher.rank(listOf(far, none, almost, halfOfTwo, oneShort, ready, readyQuick), listOf("egg", "tomato", "onion", "garlic"))
        assertEquals(listOf("ready-quick", "ready", "one-short", "almost", "half-of-two"), ranked.map { it.recipe.id })
        assertEquals(
            listOf(MatchTier.READY, MatchTier.READY, MatchTier.READY, MatchTier.ALMOST, MatchTier.ALMOST),
            ranked.map { it.tier },
        )
        // Missing the main ingredient of a two-ingredient dish isn't "ready to cook".
        assertEquals(MatchTier.ALMOST, ranked.first { it.recipe.id == "half-of-two" }.tier)
    }

    @Test fun aDishThatUsesMoreOfYourFridgeRanksFirst() {
        val big = recipe("omelette", "eggs", "onion", "tomato", "green chilli", "coriander leaves", minutes = 25)
        val tiny = recipe("boiled-egg", "eggs", minutes = 10)
        val oneShort = recipe("menemen", "eggs", "onion", "tomato", "capsicum", "butter")
        val ranked = RecipeMatcher.rank(listOf(tiny, oneShort, big), listOf("egg", "onion", "tomato", "chilli", "coriander", "capsicum"))
        assertEquals(listOf("omelette", "menemen", "boiled-egg"), ranked.map { it.recipe.id })
    }

    @Test fun shortLabelsForTheScreen() {
        assertEquals("prawns", RecipeMatcher.label("2 medium-sized prawns (shrimp), cleaned with tails removed"))
        assertEquals("bell pepper", RecipeMatcher.label("each Green, Red, and Yellow Bell Pepper, thinly sliced"))
        assertEquals("coriander leaves", RecipeMatcher.label("Finely-chopped coriander leaves, to taste"))
        assertEquals("tomato", RecipeMatcher.label("x 14.5oz cans of diced tomato - optionally blend one"))
        assertEquals("chicken thighs", RecipeMatcher.label("bone-in, skin-on chicken thighs"))
        assertEquals("ginger-garlic paste", RecipeMatcher.label("ginger-garlic paste"))
        assertEquals("paneer", RecipeMatcher.label("paneer, cubed"))
    }

    @Test fun labelsKeepTheFoodWord() {
        assertEquals("wheat flour", RecipeMatcher.label("wheat flour"))
        assertEquals("flour", RecipeMatcher.label("flour"))
        assertEquals("oat flour", RecipeMatcher.label("oat flour (1 cup)"))
        assertEquals("flour", RecipeMatcher.label("plain flour"))
    }

    @Test fun conditionsInBracketsAreNotOtherNames() {
        // "flour (500g if using yoghurt)" must not let curd count as flour.
        assertNotCovers("curd", "flour (500g if using yoghurt)")
        assertCovers("yogurt", "quark (plain yoghurt can be used as a replacement)")
        assertFalse(RecipeMatcher.isOptional("mango (or fresh mango if available), sliced"))
        assertFalse(RecipeMatcher.isOptional("grated parmesan, plus extra for serving if desired"))
    }

    @Test fun missingTheMainIngredientIsNeverReadyToCook() {
        val curry = recipe("Chicken Curry", "chicken thighs", "onion", "tomato")
        val lassi = recipe("Mango Lassi", "ripe mango, chopped", "plain yogurt", "cold milk")
        val dal = recipe("Dal Tadka", "toor dal (split pigeon peas)", "onion", "tomato", "garlic")
        val omelette = recipe("Masala Omelette", "eggs", "onion", "tomato", "cilantro")
        val ranked = RecipeMatcher.rank(listOf(curry, lassi, dal, omelette), listOf("onion", "tomato", "curd", "milk", "coriander", "garlic"))
        val tiers = ranked.associate { it.recipe.id to it.tier }
        assertEquals(MatchTier.ALMOST, tiers["Chicken Curry"]) // protein
        assertEquals(MatchTier.ALMOST, tiers["Mango Lassi"]) // named in the title
        assertEquals(MatchTier.ALMOST, tiers["Dal Tadka"]) // dal
        assertEquals(MatchTier.ALMOST, tiers["Masala Omelette"]) // eggs
        // A side ingredient is fine: one short and still ready.
        val salad = recipe("Kachumber", "cucumber", "onion", "tomato", "lemon")
        assertEquals(MatchTier.READY, RecipeMatcher.rank(listOf(salad), listOf("cucumber", "onion", "tomato")).single().tier)
        // With only the side ingredients, a dish missing its main ingredient isn't shown at all.
        val chicken = recipe("Lemon Chicken", "chicken", "lemon")
        assertTrue(RecipeMatcher.rank(listOf(chicken), listOf("lemon")).isEmpty())
    }

    @Test fun closestOnlyWhenNothingIsNearlyCookable() {
        val far = recipe("far", "egg", "tomato", "chicken", "potato", "lemon", "paneer")
        val farther = recipe("farther", "egg", "chicken", "potato", "lemon", "paneer", "cream")
        val ranked = RecipeMatcher.rank(listOf(far, farther), listOf("egg", "tomato"))
        assertEquals(listOf("far"), ranked.map { it.recipe.id })
        assertEquals(MatchTier.CLOSEST, ranked.single().tier)
    }

    @Test fun nothingUsableMeansNoResults() {
        val r = recipe("r", "egg", "tomato")
        assertTrue(RecipeMatcher.rank(listOf(r), emptyList()).isEmpty())
        assertTrue(RecipeMatcher.rank(listOf(r), listOf("salt", "oil")).isEmpty())
        assertTrue(RecipeMatcher.usable(listOf("salt", "vegetable oil", "sugar")).isEmpty())
    }

    @Test fun realCatalogFindsGreekSaladFromItsIngredients() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        val (recipes, _) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        val top = RecipeMatcher.rank(recipes, listOf("tomato", "cucumber", "red onion", "capsicum", "olive", "feta"))
        assertEquals("greek-salad", top.first().recipe.id)
        assertEquals(MatchTier.READY, top.first().tier)
    }
}
