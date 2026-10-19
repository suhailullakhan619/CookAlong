package com.cookalong.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.cookalong.app.ui.theme.CookType
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.random.Random

/** The kind of dish a recipe is, for its illustrated fallback picture. Each kind has its own colours. */
internal enum class DishKind(val dark: Color, val light: Color) {
    DRINK(Color(0xFF1F4E5F), Color(0xFF79C2C9)),
    SWEET(Color(0xFF6B2D4D), Color(0xFFE08A7A)),
    SOUP(Color(0xFF8C3B1E), Color(0xFFE39A4C)),
    SALAD(Color(0xFF2F5D3A), Color(0xFF9CC17A)),
    NOODLES(Color(0xFF7A2E1E), Color(0xFFE07A4C)),
    RICE(Color(0xFF6B5420), Color(0xFFE6C463)),
    BREAD(Color(0xFF6B4420), Color(0xFFE0B26A)),
    BREAKFAST(Color(0xFF8A5A14), Color(0xFFF2C14E)),
    CURRY(Color(0xFF7A3412), Color(0xFFE8A13A)),
    PLATE(Color(0xFF4A2F27), Color(0xFFC9785A)),
}

private fun words(vararg w: String) = Regex("\\b(" + w.joinToString("|") + ")", RegexOption.IGNORE_CASE)

private val drinkWords = words("lassi", "smoothie", "tea\\b", "chai", "coffee", "juice", "shake", "lemonade", "kvass",
    "punch", "cocktail", "sharbat", "sherbet", "latte", "cocoa", "hot chocolate", "drink", "mojito", "horchata")
private val sweetWords = words("cupcake", "cookie", "muffin", "brownie", "pudding", "ice cream", "halwa",
    "kheer", "fudge", "crumble", "custard", "brul", "mousse", "dessert", "laddu", "ladoo", "barfi", "shortbread",
    "tiramisu", "cheesecake", "doughnut", "donut", "macaroon", "meringue", "sorbet", "biscotti", "strudel")
private val cakeWords = words("cakes?\\b")
private val savouryCake = words("fish", "crab", "sardine", "salmon", "tuna", "cod\\b", "potato", "rice", "corn",
    "zucchini", "courgette", "chickpea", "lentil", "veg")
private val soupWords = words("soup", "stew", "chowder", "broth", "borscht", "bisque", "gazpacho", "soep", "minestrone", "pho\\b")
private val saladWords = words("salad", "slaw", "tabbouleh", "tabouleh", "raita", "salsa", "guacamole")
private val noodleWords = words("noodle", "ramen", "chow mein", "lo mein", "pasta", "spaghetti", "macaroni", "lasagn",
    "linguine", "penne", "fettuccine", "mostaccioli", "udon", "soba", "pad thai", "rigatoni", "tagliatelle", "gnocchi")
private val riceWords = words("rice\\b", "pulao", "pilaf", "pilau", "biryani", "risotto", "paella", "congee", "khichdi", "jambalaya")
private val breadWords = words("breads?\\b", "buns?\\b", "focaccia", "naan\\b", "flatbread", "challah", "damper",
    "zopf", "scone", "bagel", "pretzel", "arepa", "pizza", "flammkuchen", "paratha", "roti\\b", "chapati", "brioche")
private val breakfastWords = words("pancake", "waffle", "omelet", "eggs?\\b", "toast", "porridge", "oatmeal", "granola",
    "frittata", "shakshuka", "crepe", "upma", "poha")
private val curryWords = words("curry", "dal\\b", "dahl", "daal", "masala", "korma", "vindaloo", "tikka", "sambar",
    "rasam", "chana", "chole", "rajma", "paneer", "makhani", "saag", "aloo")

/** Dish kind from the title first (most specific), then the recipe's categories. */
internal fun dishKind(title: String, categories: List<String>): DishKind {
    val cats = categories.map { it.lowercase() }.toSet()
    return when {
        "drink" in cats || drinkWords.containsMatchIn(title) -> DishKind.DRINK
        "sweet" in cats || sweetWords.containsMatchIn(title) -> DishKind.SWEET
        cakeWords.containsMatchIn(title) && !savouryCake.containsMatchIn(title) -> DishKind.SWEET
        soupWords.containsMatchIn(title) -> DishKind.SOUP
        saladWords.containsMatchIn(title) -> DishKind.SALAD
        noodleWords.containsMatchIn(title) -> DishKind.NOODLES
        riceWords.containsMatchIn(title) -> DishKind.RICE
        breadWords.containsMatchIn(title) -> DishKind.BREAD
        breakfastWords.containsMatchIn(title) -> DishKind.BREAKFAST
        curryWords.containsMatchIn(title) -> DishKind.CURRY
        "soup" in cats -> DishKind.SOUP
        "salad" in cats -> DishKind.SALAD
        "noodles" in cats -> DishKind.NOODLES
        "rice" in cats -> DishKind.RICE
        "baking" in cats -> DishKind.BREAD
        "breakfast" in cats -> DishKind.BREAKFAST
        cats.any { it in setOf("indian", "north-indian", "south-indian", "thai") } -> DishKind.CURRY
        else -> DishKind.PLATE
    }
}

/**
 * The picture for a recipe without a photo (or while offline before its photo was ever downloaded): an
 * illustration of the kind of dish on that kind's colours, with the dish name. Drawn in code, so it always works.
 */
@Composable
fun DishArt(seed: String, title: String, categories: List<String>, modifier: Modifier = Modifier) {
    val kind = remember(title, categories) { dishKind(title, categories) }
    BoxWithConstraints(
        modifier
            .background(Brush.linearGradient(listOf(kind.dark, kind.light), start = Offset.Zero, end = Offset.Infinite))
            .clearAndSetSemantics { },
    ) {
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) { sprinkles(seed) }
        Row(Modifier.fillMaxSize().padding(start = h * 0.12f, end = h * 0.04f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text(
                    title.trim(),
                    style = CookType.title.copy(
                        fontSize = (h.value * 0.125f).coerceIn(15f, 40f).sp,
                        lineHeight = (h.value * 0.15f).coerceIn(18f, 48f).sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 8f),
                    ),
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Canvas(Modifier.size(h * 0.84f).padding(top = h * 0.06f)) {
                val u = size.minDimension / 100f
                drawCircle(Color.White.copy(alpha = 0.16f), 46 * u, Offset(50 * u, 54 * u))
                when (kind) {
                    DishKind.DRINK -> drink(u)
                    DishKind.SWEET -> cake(u)
                    DishKind.SOUP -> bowl(u, Color(0xFFE4572E)) { soupTop(u) }
                    DishKind.SALAD -> bowl(u, Color(0xFF6DAA45)) { saladTop(u) }
                    DishKind.NOODLES -> bowl(u, Color(0xFFD9A441)) { noodleTop(u) }
                    DishKind.RICE -> bowl(u, Color(0xFFF7F1E3)) { riceTop(u) }
                    DishKind.BREAD -> bread(u)
                    DishKind.BREAKFAST -> pancakes(u)
                    DishKind.CURRY -> bowl(u, Color(0xFFDB7F1F)) { curryTop(u) }
                    DishKind.PLATE -> plate(u)
                }
            }
        }
    }
}

private val Ceramic = Color(0xFFF7F2EA)
private val CeramicShade = Color(0xFFD9CFC0)
private val Herb = Color(0xFF4E9A3E)
private val TomatoRed = Color(0xFFE2553F)
private val Crust = Color(0xFFB8732E)
private val Golden = Color(0xFFE9B864)

/** A few faint herb leaves and peppercorns, placed by the recipe id so every card looks a little different. */
private fun DrawScope.sprinkles(seed: String) {
    val rnd = Random(seed.hashCode().absoluteValue)
    repeat(14) {
        val o = Offset(rnd.nextFloat() * size.width * 0.55f, rnd.nextFloat() * size.height)
        val r = size.height * (0.012f + rnd.nextFloat() * 0.02f)
        if (it % 3 == 0) {
            rotate(rnd.nextFloat() * 180f, o) {
                drawOval(Color.White.copy(alpha = 0.10f), o - Offset(r * 1.6f, r * 0.7f), Size(r * 3.2f, r * 1.4f))
            }
        } else {
            drawCircle(Color.White.copy(alpha = 0.07f), r * 0.6f, o)
        }
    }
}

private fun DrawScope.steam(u: Float, xs: List<Float>, baseY: Float) {
    xs.forEach { x ->
        val path = Path()
        for (k in 0..8) {
            val px = x * u + sin(k / 8f * 2 * PI).toFloat() * 2.5f * u
            val py = (baseY - k * 2.2f) * u
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(2.4f * u, cap = StrokeCap.Round))
    }
}

/** A ceramic bowl seen from the side; [top] draws what's in it above the rim line (y ≈ 58). */
private fun DrawScope.bowl(u: Float, fill: Color, top: DrawScope.() -> Unit) {
    drawRoundRect(CeramicShade, Offset(36 * u, 84 * u), Size(28 * u, 6 * u), CornerRadius(3 * u))
    drawArc(Ceramic, 0f, 180f, true, Offset(12 * u, 34 * u), Size(76 * u, 54 * u))
    drawArc(CeramicShade, 20f, 140f, false, Offset(16 * u, 40 * u), Size(68 * u, 44 * u), style = Stroke(2 * u))
    drawOval(CeramicShade, Offset(12 * u, 53 * u), Size(76 * u, 14 * u))
    drawOval(fill, Offset(15 * u, 54.5f * u), Size(70 * u, 11 * u))
    top()
}

private fun DrawScope.soupTop(u: Float) {
    drawArc(Color.White.copy(alpha = 0.85f), 200f, 220f, false, Offset(38 * u, 56 * u), Size(20 * u, 7 * u), style = Stroke(1.8f * u))
    listOf(30f to 58f, 64f to 60f, 70f to 57f).forEach { (x, y) -> drawCircle(Herb, 2.2f * u, Offset(x * u, y * u)) }
    steam(u, listOf(38f, 50f, 62f), 46f)
}

private fun DrawScope.curryTop(u: Float) {
    listOf(28f to 59f, 40f to 57f, 54f to 60f, 66f to 57f).forEach { (x, y) ->
        drawRoundRect(Color(0xFFF3E3C3), Offset((x - 3) * u, (y - 2.5f) * u), Size(7 * u, 5 * u), CornerRadius(2 * u))
    }
    listOf(34f to 61f, 48f to 56f, 60f to 62f, 72f to 59f).forEach { (x, y) -> drawCircle(Herb, 1.8f * u, Offset(x * u, y * u)) }
    steam(u, listOf(40f, 56f), 46f)
}

private fun DrawScope.saladTop(u: Float) {
    val leaves = listOf(Triple(26f, 52f, -30f), Triple(38f, 46f, 20f), Triple(52f, 44f, -10f), Triple(64f, 47f, 35f),
        Triple(74f, 53f, -25f), Triple(46f, 52f, 60f))
    leaves.forEachIndexed { i, (x, y, a) ->
        rotate(a, Offset(x * u, y * u)) {
            drawOval(if (i % 2 == 0) Herb else Color(0xFF8CC63F), Offset((x - 9) * u, (y - 5) * u), Size(18 * u, 10 * u))
        }
    }
    drawCircle(TomatoRed, 5 * u, Offset(44 * u, 50 * u))
    drawCircle(TomatoRed, 4 * u, Offset(66 * u, 53 * u))
    drawCircle(Color(0xFFD7EBB0), 4.5f * u, Offset(32 * u, 56 * u))
    drawCircle(Color(0xFF6DAA45), 4.5f * u, Offset(32 * u, 56 * u), style = Stroke(1.4f * u))
}

private fun DrawScope.noodleTop(u: Float) {
    for (row in 0 until 3) {
        val path = Path()
        for (k in 0..20) {
            val x = (20 + k * 3f) * u
            val y = (55 + row * 3 + sin(k / 20f * 6 * PI + row).toFloat() * 1.6f) * u
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFFF6D77A), style = Stroke(2.2f * u, cap = StrokeCap.Round))
    }
    drawCircle(Color.White, 6 * u, Offset(64 * u, 56 * u))
    drawCircle(Color(0xFFF2B233), 3 * u, Offset(64 * u, 56 * u))
    drawCircle(Herb, 2 * u, Offset(36 * u, 55 * u))
    drawLine(Color(0xFF8B5A2B), Offset(30 * u, 20 * u), Offset(62 * u, 58 * u), 2.6f * u, StrokeCap.Round)
    drawLine(Color(0xFF8B5A2B), Offset(38 * u, 16 * u), Offset(66 * u, 56 * u), 2.6f * u, StrokeCap.Round)
}

private fun DrawScope.riceTop(u: Float) {
    drawArc(Color(0xFFFBF8F0), 180f, 180f, true, Offset(20 * u, 40 * u), Size(60 * u, 34 * u))
    val rnd = Random(7)
    repeat(16) {
        val x = 28 + rnd.nextFloat() * 44
        val y = 47 + rnd.nextFloat() * 9
        rotate(rnd.nextFloat() * 180f, Offset(x * u, y * u)) {
            drawOval(Color(0xFFE5DCC8), Offset((x - 1.6f) * u, (y - 0.8f) * u), Size(3.2f * u, 1.6f * u))
        }
    }
    drawCircle(Herb, 2.4f * u, Offset(46 * u, 43 * u))
    drawCircle(Herb, 2 * u, Offset(52 * u, 44 * u))
    steam(u, listOf(44f, 58f), 36f)
}

private fun DrawScope.drink(u: Float) {
    val glass = Path().apply { moveTo(32 * u, 24 * u); lineTo(68 * u, 24 * u); lineTo(63 * u, 88 * u); lineTo(37 * u, 88 * u); close() }
    val liquid = Path().apply { moveTo(33.6f * u, 38 * u); lineTo(66.4f * u, 38 * u); lineTo(62 * u, 85 * u); lineTo(38 * u, 85 * u); close() }
    drawLine(Color(0xFFE85D75), Offset(56 * u, 10 * u), Offset(48 * u, 70 * u), 3 * u, StrokeCap.Round)
    drawPath(glass, Color.White.copy(alpha = 0.35f))
    drawPath(liquid, Color(0xFFF2A541))
    drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(40 * u, 44 * u), Size(9 * u, 9 * u), CornerRadius(2 * u))
    drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(51 * u, 52 * u), Size(8 * u, 8 * u), CornerRadius(2 * u))
    drawPath(glass, Color.White.copy(alpha = 0.9f), style = Stroke(1.8f * u))
    drawCircle(Color(0xFFF7E26B), 9 * u, Offset(68 * u, 26 * u))
    drawCircle(Color(0xFFFFF5B8), 6.5f * u, Offset(68 * u, 26 * u))
}

private fun DrawScope.cake(u: Float) {
    drawOval(Ceramic, Offset(14 * u, 78 * u), Size(72 * u, 12 * u))
    drawRoundRect(Color(0xFFC98A4B), Offset(22 * u, 46 * u), Size(56 * u, 36 * u), CornerRadius(5 * u))
    drawRect(Color(0xFFFFF1E0), Offset(22 * u, 61 * u), Size(56 * u, 5 * u))
    drawRoundRect(Color(0xFFFFE3EC), Offset(20 * u, 40 * u), Size(60 * u, 11 * u), CornerRadius(5 * u))
    listOf(28f, 40f, 54f, 68f).forEachIndexed { i, x ->
        drawRoundRect(Color(0xFFFFE3EC), Offset(x * u, 48 * u), Size(5 * u, (6 + i % 2 * 5) * u), CornerRadius(2.5f * u))
    }
    drawCircle(Color(0xFFD7263D), 5.5f * u, Offset(50 * u, 36 * u))
    drawLine(Color(0xFF4E7A2E), Offset(50 * u, 31 * u), Offset(55 * u, 23 * u), 1.6f * u, StrokeCap.Round)
}

private fun DrawScope.bread(u: Float) {
    drawOval(Color.Black.copy(alpha = 0.12f), Offset(16 * u, 78 * u), Size(68 * u, 10 * u))
    drawRoundRect(Crust, Offset(16 * u, 48 * u), Size(68 * u, 34 * u), CornerRadius(10 * u))
    drawOval(Color(0xFFCC8A40), Offset(16 * u, 34 * u), Size(68 * u, 32 * u))
    listOf(30f, 44f, 58f).forEach { x ->
        drawLine(Color(0xFFF2CF8C), Offset(x * u, 46 * u), Offset((x + 9) * u, 38 * u), 2.6f * u, StrokeCap.Round)
    }
    drawCircle(Color.White.copy(alpha = 0.18f), 9 * u, Offset(30 * u, 44 * u))
}

private fun DrawScope.pancakes(u: Float) {
    drawOval(Ceramic, Offset(12 * u, 76 * u), Size(76 * u, 14 * u))
    for (i in 0 until 4) {
        val y = (70 - i * 8).toFloat()
        drawOval(Color(0xFFB9772F), Offset(22 * u, y * u), Size(56 * u, 12 * u))
        drawOval(Golden, Offset(22 * u, (y - 2) * u), Size(56 * u, 11 * u))
    }
    drawRoundRect(Color(0xFFFFF1A8), Offset(43 * u, 40 * u), Size(14 * u, 7 * u), CornerRadius(2 * u))
    drawLine(Color(0xFF9C4A12), Offset(34 * u, 47 * u), Offset(32 * u, 60 * u), 3 * u, StrokeCap.Round)
    drawLine(Color(0xFF9C4A12), Offset(66 * u, 48 * u), Offset(67 * u, 56 * u), 3 * u, StrokeCap.Round)
}

private fun DrawScope.plate(u: Float) {
    // fork and knife either side of the plate
    drawLine(Color(0xFFE8E2D6), Offset(8 * u, 40 * u), Offset(8 * u, 86 * u), 2.6f * u, StrokeCap.Round)
    for (k in -1..1) drawLine(Color(0xFFE8E2D6), Offset((8 + k * 2.5f) * u, 34 * u), Offset((8 + k * 2.5f) * u, 46 * u), 1.4f * u, StrokeCap.Round)
    drawRoundRect(Color(0xFFE8E2D6), Offset(89 * u, 34 * u), Size(5 * u, 24 * u), CornerRadius(2.5f * u))
    drawLine(Color(0xFFE8E2D6), Offset(91.5f * u, 56 * u), Offset(91.5f * u, 86 * u), 2.6f * u, StrokeCap.Round)
    translate(0f, 4 * u) {
        drawOval(CeramicShade, Offset(16 * u, 58 * u), Size(68 * u, 24 * u))
        drawOval(Ceramic, Offset(16 * u, 56 * u), Size(68 * u, 23 * u))
        drawOval(Color(0xFFEDE5D8), Offset(26 * u, 60 * u), Size(48 * u, 14 * u))
        drawArc(Color(0xFFC0602A), 180f, 180f, true, Offset(30 * u, 48 * u), Size(40 * u, 24 * u))
        drawArc(Color(0xFFD9823F), 200f, 140f, true, Offset(36 * u, 52 * u), Size(28 * u, 14 * u))
        drawCircle(Herb, 3 * u, Offset(44 * u, 55 * u))
        drawCircle(TomatoRed, 3 * u, Offset(56 * u, 56 * u))
    }
    steam(u, listOf(42f, 58f), 46f)
}
