package com.cookalong.app

import com.cookalong.app.data.Ingredient
import com.cookalong.app.data.Scaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScalingTest {

    @Test fun fractionsRoundToKitchenGlyphs() {
        assertEquals("1½", Scaling.formatFraction(1.5))
        assertEquals("⅓", Scaling.formatFraction(0.34))
        assertEquals("¾", Scaling.formatFraction(0.75))
        assertEquals("2", Scaling.formatFraction(1.97))
        assertEquals("⅛", Scaling.formatFraction(0.01)) // never prints "0" for a real amount
    }

    @Test fun metricRoundsSensibly() {
        assertEquals("600", Scaling.formatMetric(600.0))
        assertEquals("535", Scaling.formatMetric(533.3))
        assertEquals("53", Scaling.formatMetric(53.3))
        assertEquals("2.5", Scaling.formatMetric(2.5))
        assertEquals("3", Scaling.formatMetric(3.0))
    }

    @Test fun scalesAndPluralisesCountableUnits() {
        val garlic = Ingredient("g", "garlic", 4.0, "clove")
        assertEquals("6 cloves garlic", Scaling.describe(garlic, Scaling.factor(4, 6)))
        assertEquals("1 clove garlic", Scaling.describe(garlic, Scaling.factor(4, 1)))
        val pasta = Ingredient("p", "spaghetti", 400.0, "g")
        assertEquals("600 g spaghetti", Scaling.describe(pasta, Scaling.factor(4, 6)))
        val eggs = Ingredient("e", "eggs", 2.0, "")
        assertEquals("3 eggs", Scaling.describe(eggs, Scaling.factor(2, 3)))
        assertEquals("1½ eggs", Scaling.describe(eggs, Scaling.factor(4, 3)))
        val flour = Ingredient("f", "flour", 1.25, "cup")
        assertEquals("2½ cups flour", Scaling.describe(flour, 2.0))
        assertEquals("1¼ cups flour", Scaling.describe(flour, 1.0))
        assertEquals("3 pinches turmeric", Scaling.describe(Ingredient("t", "turmeric", 1.0, "pinch"), 3.0))
    }

    @Test fun unquantifiedIngredientsStayReadable() {
        val salt = Ingredient("s", "salt", null, "to taste")
        assertNull(Scaling.formatQuantity(salt, 2.0))
        assertEquals("salt, to taste", Scaling.describe(salt, 2.0))
    }

    @Test fun unquantifiedNameDoesNotRepeatItsUnit() {
        val salt = Ingredient("s", "Salt to taste", null, "to taste")
        assertEquals("Salt to taste", Scaling.describe(salt, 1.0))
        assertEquals("Salt to taste", Scaling.unquantifiedName(salt))
    }

    @Test fun durations() {
        assertEquals("9:00", Scaling.formatDuration(540))
        assertEquals("1:05:00", Scaling.formatDuration(3900))
        assertEquals(1L, Scaling.displaySeconds(1)) // rounds up: never shows 0:00 while running
        assertEquals("1 minute 10 seconds", Scaling.spokenDuration(70))
    }

    @Test fun factorGuardsBadBaseServings() {
        assertEquals(1.0, Scaling.factor(0, 4), 0.0)
    }
}
