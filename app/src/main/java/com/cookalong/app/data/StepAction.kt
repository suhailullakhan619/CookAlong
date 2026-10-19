package com.cookalong.app.data

/** The kind of cooking a step involves; picks the looping animation shown with the step. */
enum class StepAction {
    BOIL, STIR, CHOP, FRY, BAKE, MIX, MICROWAVE, POUR, REST, SERVE;

    companion object {
        /**
         * A step's explicit `"animation"` in recipes.json wins (e.g. "boil"); otherwise it's guessed from the text.
         * Rules are ordered: specific actions before generic ones ("Rest, then serve" → REST, not SERVE).
         */
        fun of(step: Step): StepAction =
            step.animation?.let { name -> entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
                ?: detect(step.instructionText)

        private val rules: List<Pair<StepAction, Regex>> = listOf(
            MICROWAVE to Regex("\\bmicrowave"),
            BAKE to Regex("\\b(oven|roast|bake)"),
            MIX to Regex("\\b(whisk|mix|mash|fold|beat|blend|combine)"),
            BOIL to Regex("\\b(boil|simmer|al dente)"),
            FRY to Regex("\\b(fry|scramble|flip|pan\\b|wok|skillet|sear)"),
            CHOP to Regex("\\b(chop|slice|dice|halve|zest|grate|cut|chunk|mince)"),
            POUR to Regex("\\b(drain|pour|scoop out)"),
            REST to Regex("\\b(rest|cool|let it|wait)"),
            SERVE to Regex("\\b(serve|plate|eat|enjoy)"),
        )

        fun detect(text: String): StepAction {
            val t = text.lowercase()
            return rules.firstOrNull { it.second.containsMatchIn(t) }?.first ?: STIR
        }
    }
}

/** Turns on-screen text into something a TTS voice reads naturally. */
object Speech {
    /** "Step 2. Add the spaghetti…" (just the step number; the screen shows "of N"). */
    fun forStep(stepIndex: Int, text: String): String =
        "Step ${stepIndex + 1}. ${clean(text)}"

    fun clean(text: String): String = text
        .replace(Regex("(\\d)\\s*°\\s*C\\b"), "$1 degrees Celsius")
        .replace(Regex("(\\d)\\s*°\\s*F\\b"), "$1 degrees Fahrenheit")
        .replace(" / ", " or ")
        .replace(Regex("(\\d)½"), "$1 and a half").replace("½", "half")
        .replace(Regex("(\\d)¼"), "$1 and a quarter").replace("¼", "a quarter")
        .replace(Regex("(\\d)¾"), "$1 and three quarters").replace("¾", "three quarters")
        .replace(Regex("(\\d)⅓"), "$1 and a third").replace("⅓", "a third")
        .replace(Regex("(\\d)⅔"), "$1 and two thirds").replace("⅔", "two thirds")
        .replace(Regex("(\\d)⅛"), "$1 and an eighth").replace("⅛", "an eighth")

    private val unitNames = mapOf(
        "g" to "gram", "kg" to "kilogram", "ml" to "millilitre", "l" to "litre", "tbsp" to "tablespoon", "tsp" to "teaspoon",
    )
    private val fractionOfA = mapOf(
        "½" to "half a", "¼" to "a quarter", "¾" to "three quarters of a", "⅓" to "a third of a",
        "⅔" to "two thirds of a", "⅛" to "an eighth of a",
    )
    private val quantity = Regex("^([0-9.]+)?([½¼¾⅓⅔⅛])?\\s+(g|kg|ml|l|tbsp|tsp)\\b")

    /** An ingredient line read aloud: "400 g spaghetti" -> "400 grams spaghetti", "½ tsp salt" -> "half a teaspoon salt". */
    fun ingredient(line: String): String {
        val m = quantity.find(line) ?: return clean(line)
        val (whole, fraction, unit) = m.destructured
        if (whole.isEmpty() && fraction.isEmpty()) return clean(line)
        val name = unitNames.getValue(unit)
        val spoken = when {
            whole.isEmpty() -> "${fractionOfA.getValue(fraction)} $name"
            fraction.isEmpty() && whole.toDoubleOrNull() == 1.0 -> "1 $name"
            else -> "$whole$fraction ${name}s"
        }
        return clean(spoken + line.substring(m.range.last + 1))
    }
}
