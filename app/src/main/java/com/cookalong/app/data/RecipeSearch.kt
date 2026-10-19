package com.cookalong.app.data

/**
 * Voice search ("Alexa, tell Cook Along to find paneer recipes"). Every word of the query has to appear somewhere
 * in the recipe: title, ingredients, cuisine, diet or description. Title hits rank highest. Indian, British and US
 * names for the same food count as one ("capsicum" finds bell pepper recipes, "brinjal" finds aubergine ones), using
 * the same synonyms as the fridge scan. Unit-tested in RecipeSearchTest.
 */
object RecipeSearch {

    private val ignored = setOf(
        "a", "an", "the", "some", "and", "with", "for", "me", "my", "recipe", "recipes", "dish", "dishes", "of", "to",
        "make", "cook", "something", "please", "food",
    )
    private val wordRe = Regex("[a-z]+")

    fun words(text: String): List<String> = wordRe.findAll(text.lowercase()).map { stem(it.value) }.toList()

    /** The meaningful words of a spoken query: filler is dropped before stemming, so "dishes" isn't left as "dishe". */
    private fun wanted(query: String): List<String> =
        wordRe.findAll(query.lowercase()).map { it.value }.filterNot { it in ignored }.map(::stem).filterNot { it in ignored }.toList()

    /** Crude plural folding: "tomatoes" ~ "tomato", "eggs" ~ "egg", "curries" ~ "curry". */
    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("oes") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun find(recipes: List<Recipe>, query: String, maxMinutes: Int? = null, limit: Int = 24): List<Recipe> {
        val wanted = wanted(query).distinct()
        val pool = recipes.filter { maxMinutes == null || it.totalMinutes <= maxMinutes }
        if (wanted.isEmpty()) return if (maxMinutes == null) emptyList() else pool.sortedBy { it.totalMinutes }.take(limit)
        // A query word may be another name for a food: "capsicum" -> [bell, pepper], matched when all of those are present.
        val folded = wanted.map { w -> w to RecipeMatcher.canonical(w).takeIf { it.isNotEmpty() && it != listOf(w) } }
        fun Set<String>.has(w: Pair<String, List<String>?>) = w.first in this || w.second?.let { this.containsAll(it) } == true
        return pool.mapNotNull { r ->
            val title = words(r.title).toSet() + RecipeMatcher.canonical(r.title)
            val ingredients = r.ingredients.flatMap { words(it.name) + RecipeMatcher.canonical(it.name) }.toSet()
            val other = (r.categories + r.dietaryTags).flatMap { words(it) }.toSet() + words(r.description)
            var score = 0
            for (w in folded) {
                score += when {
                    title.has(w) -> 3
                    other.has(w) -> 2
                    ingredients.has(w) -> 1
                    else -> return@mapNotNull null
                }
            }
            r to score
        }
            .sortedWith(compareByDescending<Pair<Recipe, Int>> { it.second }.thenBy { it.first.totalMinutes })
            .take(limit)
            .map { it.first }
    }

    /** The one recipe the user named outright ("open dal tadka"), if exactly one title matches the whole query. */
    fun exactTitle(recipes: List<Recipe>, query: String): Recipe? {
        val wanted = wanted(query)
        if (wanted.isEmpty()) return null
        val hits = recipes.filter { words(it.title).filterNot { w -> w in ignored } == wanted }
        return hits.singleOrNull()
    }
}
