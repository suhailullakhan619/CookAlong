package com.cookalong.app

import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeMatcher
import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.data.RemoteCatalog
import kotlinx.serialization.Serializable
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measurement harness, not a regular test: runs realistic Amazon Nova fridge-scan outputs against the REAL catalog
 * (bundled recipes.json + the online catalog from GET /recipes) and writes what comes out, and why, to
 * build/reports/fridge-scan.md. Needs the network, so it only runs when asked:
 *
 *   COOKALONG_HARNESS=1 ./gradlew :app:testDebugUnitTest --tests '*FridgeScanQualityTest*'
 */
class FridgeScanQualityTest {

    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }

    /** What Nova returns for typical fridges: Indian/British names, US names, and the awkward cases. */
    private val fridges = linkedMapOf(
        "Indian fridge, Indian-English names" to listOf(
            "onion", "tomato", "paneer", "capsicum", "curd", "coriander", "green chilli", "potato", "egg", "milk",
            "spinach", "rice", "atta",
        ),
        "Same fridge, US names" to listOf(
            "onion", "tomato", "paneer", "green bell pepper", "yogurt", "cilantro", "green chili", "potato", "egg",
            "milk", "spinach", "rice", "whole wheat flour",
        ),
        "Example from a real scan" to listOf("rice", "carrot", "pea", "bay leaf", "chili"),
        "Breakfast fridge" to listOf("egg", "milk", "bread", "butter", "cheese", "tomato", "onion"),
        "Dal-chawal kitchen" to listOf("toor dal", "rice", "onion", "tomato", "garlic", "ginger", "ghee", "lemon"),
        "Chicken night" to listOf("chicken", "onion", "tomato", "ginger", "garlic", "yogurt", "lemon", "green chilli"),
        "Veg drawer" to listOf("carrot", "cabbage", "cauliflower", "peas", "beans", "cucumber", "lemon", "ginger", "garlic"),
        "Pakora snack" to listOf("potato", "besan", "onion", "green chilli", "coriander leaves"),
        "Brinjal and bhindi" to listOf("brinjal", "lady finger", "onion", "tomato", "green chillies"),
        "British names" to listOf("aubergine", "courgette", "spring onion", "coriander", "chilli", "plain flour", "double cream"),
        "South Indian" to listOf("rice", "urad dal", "coconut", "curry leaves", "mustard seeds", "green chilli", "onion"),
        "Fruit and dairy" to listOf("banana", "apple", "milk", "yogurt", "honey", "oats"),
        "Paneer dinner" to listOf("paneer", "capsicum", "onion", "tomato", "cream", "ginger", "garlic", "green chilli", "butter"),
        "Rajma chawal" to listOf("rajma", "rice", "onion", "tomato", "ginger", "garlic", "coriander"),
        "Aloo gobi" to listOf("potato", "cauliflower", "tomato", "onion", "ginger", "green chilli", "coriander leaves"),
        "Egg bhurji" to listOf("egg", "onion", "tomato", "green chilli", "coriander leaves", "bread", "butter"),
        "Sooji breakfast" to listOf("semolina", "onion", "carrot", "green chilli", "curry leaves", "peanut", "lemon"),
        "Chole" to listOf("chickpeas", "onion", "tomato", "ginger", "garlic", "green chilli", "lemon", "coriander"),
        "Baking cupboard" to listOf("flour", "sugar", "butter", "egg", "milk", "baking powder", "vanilla", "chocolate"),
        "Pasta night" to listOf("pasta", "tomato", "garlic", "basil", "parmesan", "onion", "olive oil", "mushroom"),
        "Mexican" to listOf("tortilla", "avocado", "tomato", "onion", "lime", "cilantro", "black beans", "cheese", "jalapeno"),
        "Chinese stir-fry" to listOf("rice", "egg", "spring onion", "carrot", "peas", "soy sauce", "garlic", "ginger", "tofu"),
        "Mediterranean" to listOf("cucumber", "tomato", "feta", "olives", "red onion", "lemon", "chickpeas", "yogurt", "mint"),
        "Thai" to listOf("coconut milk", "lemongrass", "chicken", "lime", "garlic", "chili", "ginger", "basil", "fish sauce"),
        "Fish curry" to listOf("fish", "coconut", "tomato", "onion", "tamarind", "curry leaves", "green chilli", "ginger"),
        "Vegan bowl" to listOf("rice", "chickpeas", "spinach", "carrot", "avocado", "lemon", "tahini", "cucumber"),
        "Soup" to listOf("carrot", "celery", "onion", "potato", "garlic", "leek", "vegetable stock"),
        "Dessert" to listOf("milk", "sugar", "rice", "cardamom", "almond", "raisin", "ghee", "saffron"),
        "Nova style long list" to listOf("red onion", "roma tomato", "baby spinach", "cheddar cheese", "whole milk", "eggs", "butter", "bell pepper", "carrot", "cucumber", "lemon", "ginger", "garlic", "yogurt", "orange juice", "ketchup", "mustard"),
        "Single item" to listOf("egg"),
        "Fruit bowl" to listOf("apple", "banana", "orange", "mango", "grapes"),
        "Only staples" to listOf("salt", "oil", "sugar", "ketchup", "garam masala"),
        "Not food" to emptyList(),
    )

    @Test fun report() {
        assumeTrue("set COOKALONG_HARNESS=1 to run the fridge-scan harness", System.getenv("COOKALONG_HARNESS") != null)
        val (bundled, _) = RecipeRepository.parseCatalog(File(assets, "recipes.json").readText())
        val online = onlineRecipes()
        val catalog = RemoteCatalog.combine(bundled, RemoteCatalog.merge(emptyList(), online).first)
        val out = StringBuilder()
        out.appendLine("# Fridge scan quality — ${catalog.size} recipes (${bundled.size} bundled + ${catalog.size - bundled.size} online)\n")
        // Real Nova answers recorded by a scan run: COOKALONG_SCANS=<json list of [file, status, [names], note, seconds]>
        val recorded = System.getenv("COOKALONG_SCANS")?.let { path ->
            kotlinx.serialization.json.Json.parseToJsonElement(File(path).readText()).let { it as kotlinx.serialization.json.JsonArray }
                .map { row ->
                    val a = row as kotlinx.serialization.json.JsonArray
                    "Nova: " + (a[0] as kotlinx.serialization.json.JsonPrimitive).content to
                        (a[2] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.removeSuffix("?") }
                }.filter { it.second.isNotEmpty() }
        }.orEmpty()
        (fridges.toList() + recorded).forEach { (name, detected) ->
            val ranked = RecipeMatcher.rank(catalog, detected)
            val usable = RecipeMatcher.usable(detected)
            out.appendLine("## $name")
            out.appendLine("Detected: ${detected.joinToString(", ").ifEmpty { "(nothing)" }}  ")
            out.appendLine("Ignored as staples: ${(detected - usable.toSet()).joinToString(", ").ifEmpty { "none" }}  ")
            out.appendLine("Shown: " + ranked.groupingBy { it.tier }.eachCount().entries.joinToString { "${it.key} ${it.value}" } + "\n")
            ranked.forEachIndexed { i, m ->
                out.appendLine(
                    "${i + 1}. ${m.tier} **${m.recipe.title}** — ${m.have.size}/${m.needed}, ${m.recipe.totalMinutes} min" +
                        " · have: ${m.have.joinToString { it.name }}" +
                        (if (m.missing.isEmpty()) "" else " · need: ${m.missing.joinToString { it.name }}"),
                )
            }
            out.appendLine()
        }
        val file = File("build/reports/fridge-scan.md")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        println(out)

        // How every ingredient line in the catalog is understood, most common first.
        val lines = catalog.flatMap { r -> r.ingredients.map { it.name } }.groupingBy { it }.eachCount()
        val vocab = lines.entries.sortedByDescending { it.value }
            .joinToString("\n") { (n, c) -> "$c\t$n\t=> ${RecipeMatcher.explain(n)}" }
        File("build/reports/fridge-scan-vocab.tsv").writeText(vocab)
    }

    @Serializable private data class Page(val recipes: List<Recipe> = emptyList(), val next: String? = null)

    /** GET /recipes, following `next` pages; cached for an hour in build/tmp so reruns are quick. */
    private fun onlineRecipes(): List<Recipe> {
        val cache = File("build/tmp/online-recipes.json")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 3_600_000) {
            return RecipeRepository.json.decodeFromString(Page.serializer(), cache.readText()).recipes
        }
        val base = RecipeRepository.parseConfig(File(assets, "config.json").readText()).recipesApiUrl.trimEnd('/')
        val all = ArrayList<Recipe>()
        var next: String? = null
        do {
            val url = "$base/recipes" + (next?.let { "?next=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            val page = RecipeRepository.json.decodeFromString(Page.serializer(), conn.inputStream.bufferedReader().readText())
            all += page.recipes
            next = page.next
        } while (next != null && all.size < 5_000)
        cache.parentFile.mkdirs()
        cache.writeText(RecipeRepository.json.encodeToString(Page.serializer(), Page(all)))
        return all
    }
}
