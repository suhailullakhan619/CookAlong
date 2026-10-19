package com.cookalong.app

import com.cookalong.app.ui.components.DishKind
import com.cookalong.app.ui.components.dishKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The illustrated fallback picture matches the kind of dish. */
class DishKindTest {
    @Test
    fun titleWinsOverCategories() {
        assertEquals(DishKind.SOUP, dishKind("Turkish Red Lentil Soup", listOf("middle-eastern", "healthy")))
        assertEquals(DishKind.RICE, dishKind("Vegetable Pulao", listOf("indian")))
        assertEquals(DishKind.NOODLES, dishKind("Tofu and Cashew Chow Mein", listOf("chinese")))
        assertEquals(DishKind.SALAD, dishKind("Perfect Potato Salad", emptyList()))
        assertEquals(DishKind.BREAD, dishKind("Challah Bread", listOf("baking")))
    }

    @Test
    fun savouryPiesAndEggsAreNotDesserts() {
        assertEquals(DishKind.PLATE, dishKind("Shepherd's Pie", emptyList()))
        assertEquals(DishKind.PLATE, dishKind("Sardine Cakes", emptyList()))
        assertEquals(DishKind.PLATE, dishKind("Mexican Meat Loaf", listOf("mexican")))
        assertEquals(DishKind.PLATE, dishKind("Eggplant Parmesan", emptyList()))
        assertEquals(DishKind.SWEET, dishKind("Yogurt Cake", listOf("baking")))
        assertEquals(DishKind.BREAKFAST, dishKind("Masala Omelette", listOf("indian", "breakfast")))
    }

    @Test
    fun categoriesDecideWhenTheTitleSaysNothing() {
        assertEquals(DishKind.SWEET, dishKind("Kaiserschmarrn", listOf("sweet")))
        assertEquals(DishKind.DRINK, dishKind("Madras Filter Coffee", listOf("drink", "indian")))
        assertEquals(DishKind.CURRY, dishKind("Spicy Chilli Chicken", listOf("indian")))
        assertEquals(DishKind.PLATE, dishKind("Fajitas", listOf("mexican")))
    }
}
