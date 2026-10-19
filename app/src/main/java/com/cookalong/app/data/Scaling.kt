package com.cookalong.app.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Pure helpers for scaling and printing ingredient quantities. Unit-tested in ScalingTest. */
object Scaling {

    private val metricUnits = setOf("g", "kg", "ml", "l")
    private val countableUnits = setOf("clove", "can", "handful", "pinch", "sprig", "slice", "cup")
    private val fractions = listOf(
        0.0 to "", 0.125 to "⅛", 0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾", 1.0 to "",
    )

    fun factor(baseServings: Int, servings: Int): Double =
        if (baseServings <= 0) 1.0 else servings.toDouble() / baseServings

    fun scaledAmount(ingredient: Ingredient, factor: Double): Double? = ingredient.amount?.let { it * factor }

    /** "400 g spaghetti", "1½ tbsp olive oil", "3 cloves garlic", "salt, to taste". */
    fun describe(ingredient: Ingredient, factor: Double): String {
        val qty = formatQuantity(ingredient, factor)
        return if (qty == null) unquantifiedName(ingredient) else "$qty ${ingredient.name}"
    }

    /** "salt, to taste": the name plus its unit when there is no amount, without repeating a unit the name already says. */
    fun unquantifiedName(ingredient: Ingredient): String = "${ingredient.name}${unitSuffixForUnquantified(ingredient)}"

    /** The quantity + unit part only, or null when the ingredient has no amount. */
    fun formatQuantity(ingredient: Ingredient, factor: Double): String? {
        val amount = scaledAmount(ingredient, factor) ?: return null
        val unit = ingredient.unit.trim()
        val number = if (unit.lowercase() in metricUnits) formatMetric(amount) else formatFraction(amount)
        val unitText = when {
            unit.isEmpty() -> ""
            unit.lowercase() in countableUnits && amount > 1.0001 -> " " + plural(unit)
            else -> " $unit"
        }
        return number + unitText
    }

    /** "pinch" -> "pinches", "clove" -> "cloves". */
    private fun plural(unit: String): String =
        if (unit.endsWith("ch") || unit.endsWith("sh") || unit.endsWith("s")) unit + "es" else unit + "s"

    private fun unitSuffixForUnquantified(ingredient: Ingredient): String =
        if (ingredient.unit.isBlank() || ingredient.name.contains(ingredient.unit.trim(), ignoreCase = true)) "" else ", ${ingredient.unit}"

    /** Grams/millilitres: no fractions, rounded to a sensible kitchen precision. */
    fun formatMetric(amount: Double): String = when {
        amount >= 100 -> ((amount / 5).roundToLong() * 5).toString()
        amount >= 10 -> amount.roundToLong().toString()
        else -> {
            val r = (amount * 10).roundToLong() / 10.0
            if (r == floor(r)) r.toLong().toString() else r.toString()
        }
    }

    /** Spoons, cups and whole items: nearest common kitchen fraction, e.g. 1.5 -> "1½", 0.33 -> "⅓". */
    fun formatFraction(amount: Double): String {
        if (amount <= 0) return "0"
        var whole = floor(amount).toLong()
        val frac = amount - whole
        val (value, glyph) = fractions.minBy { abs(it.first - frac) }
        if (value == 1.0) whole += 1
        return when {
            whole == 0L && glyph.isEmpty() -> fractions[1].second // tiny amounts never print as "0"
            whole == 0L -> glyph
            else -> "$whole$glyph"
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Seconds remaining shown to the user, rounding up so a timer never reads 0:00 while still running. */
    fun displaySeconds(remainingMs: Long): Long = (remainingMs + 999) / 1000

    fun minutesLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    fun spokenDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return buildList {
            if (m > 0) add(if (m == 1) "1 minute" else "$m minutes")
            if (s > 0) add(if (s == 1) "1 second" else "$s seconds")
        }.joinToString(" ").ifEmpty { "0 seconds" }
    }
}
