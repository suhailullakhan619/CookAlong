package com.cookalong.app

import com.cookalong.app.companion.CompanionServer
import com.cookalong.app.data.RecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

class CompanionServerTest {

    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
    private val recipes = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText()).first
    private val server = CompanionServer(
        pageTemplate = { File(assets, "companion/index.html").readText() },
        findRecipe = { id -> recipes.firstOrNull { it.id == id } },
        readAsset = { path -> File(assets, path).takeIf { it.exists() }?.readBytes() },
    )

    @Test fun servesDishPicturesOnly() {
        val ok = server.route("/images/masala-omelette.jpg")
        assertEquals(200, ok.status)
        assertEquals("image/jpeg", ok.contentType)
        assertTrue(ok.bytes.size > 1000)
        val photo = server.route("/images/pavlova.jpg")
        assertEquals(200, photo.status)
        assertEquals("image/jpeg", photo.contentType)
        assertEquals(404, server.route("/images/../recipes.json").status)
        assertEquals(404, server.route("/images/nope.png").status)
    }

    @Test fun servesScaledListInjectedIntoThePage() {
        val r = server.route("/r/lemon-garlic-spaghetti?s=6")
        assertEquals(200, r.status)
        assertTrue(r.body.contains("window.COOKALONG_DATA="))
        assertTrue(r.body.contains("\"t\":\"Lemon Garlic Spaghetti\""))
        assertTrue(r.body.contains("\"s\":6"))
        assertTrue(r.body.contains("\"r\":\"lemon-garlic-spaghetti\""))
        assertTrue(r.body.contains("600 g spaghetti"))
        assertFalse("marker must be replaced", r.body.contains(CompanionServer.DATA_MARKER))
    }

    @Test fun unknownPathsAndRecipesAre404() {
        assertEquals(404, server.route("/").status)
        assertEquals(404, server.route("/r/nope?s=2").status)
        assertEquals(404, server.route("/../../etc/passwd").status)
    }

    @Test fun badServingsFallBackSafely() {
        assertTrue(server.route("/r/masala-omelette?s=abc").body.contains("\"s\":1"))
        assertTrue(server.route("/r/masala-omelette?s=100000").body.contains("\"s\":99"))
    }

    @Test fun endToEndOverARealSocket() {
        val base = server.start(hostOverride = "127.0.0.1")!!
        try {
            val html = URL("$base/r/chocolate-mug-cake?s=2").readText()
            assertTrue(html.contains("Chocolate Mug Cake"))
            assertTrue(html.contains("8 tbsp plain flour"))
        } finally {
            server.stop()
        }
    }
}
