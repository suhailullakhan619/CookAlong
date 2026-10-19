package com.cookalong.app

import com.cookalong.app.data.CookingSession
import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.data.RowRule
import com.cookalong.app.data.ShoppingList
import com.cookalong.app.data.ShoppingQr
import com.cookalong.app.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {

    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }

    @Test fun bundledCatalogIsValid() {
        val (recipes, skipped) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        assertEquals("every bundled recipe should be valid", 0, skipped)
        assertTrue(recipes.size >= 6)
        recipes.forEach { r ->
            val ids = r.ingredients.map { it.id }.toSet()
            r.steps.forEach { s ->
                s.relatedIngredientIds.forEach { assertTrue("${r.id}/${s.id} -> unknown ingredient $it", it in ids) }
                s.suggestedTimerSeconds?.let { assertTrue(it > 0) }
            }
            assertEquals("${r.id} step order should be 1..n", (1..r.steps.size).toList(), r.orderedSteps.map { it.order })
            r.image?.let { assertTrue("${r.id} image missing: $it", File(assets, it).exists()) }
        }
        val config = RecipeRepository.parseConfig(File(assets, "config.json").readText())
        assertTrue(config.rows.isNotEmpty())
    }

    @Test fun invalidAndDuplicateRecipesAreSkippedNotFatal() {
        val json = """
            {"recipes":[
              {"id":"a","title":"A","servings":2,"steps":[{"id":"s","order":1,"instructionText":"x"}]},
              {"id":"a","title":"Dup","steps":[{"id":"s","order":1,"instructionText":"x"}]},
              {"id":"b","title":"No steps"},
              {"id":"c","title":"Bad servings","servings":0,"unknownField":true,"steps":[{"id":"s","order":1,"instructionText":"x"}]}
            ]}
        """.trimIndent()
        val (recipes, skipped) = RecipeRepository.parseCatalog(json)
        assertEquals(listOf("a", "c"), recipes.map { it.id })
        assertEquals(2, skipped)
        assertEquals(1, recipes[1].servings)
    }

    @Test fun rowRules() {
        val (recipes, _) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        val quick = recipes.filter(RowRule("q", maxTotalMinutes = 20)::matches)
        assertTrue(quick.all { it.totalMinutes <= 20 } && quick.isNotEmpty())
        val veg = recipes.filter(RowRule("v", anyTags = listOf("vegan"))::matches)
        assertTrue(veg.all { "vegan" in it.dietaryTags })
    }

    @Test fun sessionRoundTripsThroughJson() {
        val s = CookingSession(
            "lemon-garlic-spaghetti", 3, 6,
            listOf(KitchenTimer("t", "Pasta", 540, TimerStatus.PAUSED, 120_000, 0, "s2")),
        )
        val text = RecipeRepository.json.encodeToString(CookingSession.serializer(), s)
        assertEquals(s, RecipeRepository.json.decodeFromString(CookingSession.serializer(), text))
    }

    @Test fun shoppingListQrPayloads() {
        val (recipes, _) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        val pasta = recipes.first { it.id == "lemon-garlic-spaghetti" }
        // Default with a LAN address: short link to this TV
        val lan = ShoppingList.qr(pasta, 6, "", "http://192.168.1.20:8765")
        assertEquals(ShoppingQr.Mode.LAN, lan.mode)
        assertEquals("http://192.168.1.20:8765/r/lemon-garlic-spaghetti?s=6", lan.payload)
        // No network: ASCII-only text (no UTF-8 header needed in the QR)
        val text = ShoppingList.qr(pasta, 6, "", null)
        assertEquals(ShoppingQr.Mode.TEXT, text.mode)
        assertTrue(text.payload.startsWith("CookAlong shopping list\nLemon Garlic Spaghetti (for 6 servings)"))
        assertTrue(text.payload.contains("- 600 g spaghetti"))
        assertTrue(text.payload.contains("- 1 1/2 lemon, zest and juice"))
        assertTrue(text.payload.contains("- 3/4 tsp chili flakes"))
        assertTrue(recipes.all { r -> ShoppingList.plainText(r, 12).all { it.code < 128 } })
        // Hosted companion page wins when configured
        val hosted = ShoppingList.qr(pasta, 2, "https://example.org/cookalong/", "http://192.168.1.20:8765")
        assertEquals(ShoppingQr.Mode.HOSTED, hosted.mode)
        assertTrue(hosted.payload.startsWith("https://example.org/cookalong/#r=lemon-garlic-spaghetti&t=Lemon%20Garlic%20Spaghetti&s=2&i="))
        assertTrue("hosted QR must stay comfortably scannable", recipes.all { ShoppingList.hostedUrl(it, 12, "https://example.github.io/cookalong/").length < 900 })
        assertTrue("QR payloads must stay well inside QR capacity", recipes.all { ShoppingList.plainText(it, 12).length < 1500 })
    }
}
