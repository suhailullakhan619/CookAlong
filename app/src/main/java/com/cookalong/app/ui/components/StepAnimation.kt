package com.cookalong.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.cookalong.app.data.StepAction
import com.cookalong.app.ui.theme.LocalCookColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Steel = Color(0xFF9AA4AE)
private val SteelDark = Color(0xFF6E7781)
private val Flame = Color(0xFFFF8A3D)
private val FlameCore = Color(0xFFFFD166)
private val Sauce = Color(0xFFE0893A)
private val Water = Color(0xFF7FB8E0)
private val Board = Color(0xFFC08A55)
private val Leaf = Color(0xFF5DAA4F)
private val Tomato = Color(0xFFE2553F)
private val Dough = Color(0xFFF2D49B)
private val Plate = Color(0xFFF4EFE6)

/**
 * A small looping illustration of what the current step involves (boiling, chopping, frying…). Drawn in code,
 * so it works offline and needs no video files. Decorative only: hidden from screen readers.
 */
@Composable
fun StepAnimation(action: StepAction, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    val transition = rememberInfiniteTransition(label = "step-anim")
    // t: 0→1 every 1.6 s, used for continuous motion (bubbles, rotation, steam)
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "t")
    // s: 0→1→0 every 0.45 s, used for quick back-and-forth motion (chopping, shaking, whisking)
    val s by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "s")
    val steam = c.textMuted.copy(alpha = 0.55f)

    Canvas(modifier.clearAndSetSemantics { }) {
        drawCircle(c.surfaceRaised, radius = size.minDimension * 0.5f)
        val u = size.minDimension / 100f // drawing units: the scene is designed on a 100×100 grid
        translate((size.width - 100 * u) / 2, (size.height - 100 * u) / 2) {
            when (action) {
                StepAction.BOIL -> boil(u, t, steam)
                StepAction.STIR -> stir(u, t)
                StepAction.CHOP -> chop(u, s, t)
                StepAction.FRY -> fry(u, s, t)
                StepAction.BAKE -> bake(u, t, steam)
                StepAction.MIX -> mix(u, s)
                StepAction.MICROWAVE -> microwave(u, t)
                StepAction.POUR -> pour(u, t)
                StepAction.REST -> rest(u, t, steam, c.accent)
                StepAction.SERVE -> serve(u, t, steam)
            }
        }
    }
}

private fun DrawScope.steamWisps(u: Float, t: Float, color: Color, xs: List<Float>, baseY: Float) {
    xs.forEachIndexed { i, x ->
        val phase = (t + i * 0.33f) % 1f
        val path = Path()
        for (k in 0..10) {
            val y = baseY - k * 2.2f * u - phase * 6 * u
            val px = x * u + sin((k / 10f + phase) * 2 * PI).toFloat() * 3 * u
            if (k == 0) path.moveTo(px, y) else path.lineTo(px, y)
        }
        drawPath(path, color.copy(alpha = color.alpha * (1 - phase)), style = Stroke(2.5f * u, cap = StrokeCap.Round))
    }
}

private fun DrawScope.flames(u: Float, t: Float, cx: Float, y: Float) {
    for (i in -1..1) {
        val h = (7 + 3 * sin((t * 2 * PI + i).toFloat())) * u
        val x = (cx + i * 9) * u
        drawOval(Flame, Offset(x - 3 * u, y * u - h), Size(6 * u, h))
        drawOval(FlameCore, Offset(x - 1.5f * u, y * u - h * 0.55f), Size(3 * u, h * 0.55f))
    }
}

private fun DrawScope.boil(u: Float, t: Float, steam: Color) {
    flames(u, t, 50f, 86f)
    drawRoundRect(Steel, Offset(22 * u, 48 * u), Size(56 * u, 32 * u), CornerRadius(6 * u))
    drawRect(SteelDark, Offset(16 * u, 50 * u), Size(6 * u, 4 * u))
    drawRect(SteelDark, Offset(78 * u, 50 * u), Size(6 * u, 4 * u))
    drawOval(Water, Offset(23 * u, 43 * u), Size(54 * u, 10 * u))
    for (i in 0 until 5) {
        val p = (t + i * 0.2f) % 1f
        drawCircle(Color.White.copy(alpha = 0.9f * (1 - p)), (1.5f + 2 * p) * u, Offset((30 + i * 10) * u, (50 - p * 10) * u))
    }
    steamWisps(u, t, steam, listOf(38f, 50f, 62f), 38f)
}

private fun DrawScope.stir(u: Float, t: Float) {
    val center = Offset(50 * u, 54 * u)
    drawCircle(SteelDark, 32 * u, center)
    drawCircle(Sauce, 27 * u, center)
    drawArc(Color.White.copy(alpha = 0.35f), t * 360f, 200f, false, Offset(31 * u, 35 * u), Size(38 * u, 38 * u), style = Stroke(2.5f * u))
    for (i in 0 until 4) {
        val a = (t * 2 * PI + i * PI / 2).toFloat()
        drawCircle(Leaf, 2.5f * u, center + Offset(cos(a) * 16 * u, sin(a) * 16 * u))
    }
    // the spoon circles the pot
    val a = (t * 2 * PI).toFloat()
    val tip = center + Offset(cos(a) * 14 * u, sin(a) * 14 * u)
    drawLine(Board, tip, Offset(tip.x + 18 * u, tip.y - 34 * u), 5 * u, StrokeCap.Round)
    drawOval(Board, Offset(tip.x - 5 * u, tip.y - 3 * u), Size(10 * u, 7 * u))
}

private fun DrawScope.chop(u: Float, s: Float, t: Float) {
    drawRoundRect(Board, Offset(14 * u, 64 * u), Size(72 * u, 16 * u), CornerRadius(4 * u))
    // pieces drift away from the knife
    for (i in 0 until 4) {
        val x = 52 + i * 8 + (t * 8) % 8
        drawRoundRect(if (i % 2 == 0) Tomato else Leaf, Offset(x * u, 58 * u), Size(5 * u, 6 * u), CornerRadius(1.5f * u))
    }
    drawRoundRect(Tomato, Offset(20 * u, 54 * u), Size(24 * u, 10 * u), CornerRadius(4 * u))
    val lift = s * 18 * u
    translate(0f, -lift) {
        val blade = Path().apply {
            moveTo(44 * u, 30 * u); lineTo(50 * u, 30 * u); lineTo(50 * u, 62 * u); lineTo(42 * u, 62 * u); close()
        }
        drawPath(blade, Steel)
        drawRoundRect(Color(0xFF3B2A20), Offset(44 * u, 16 * u), Size(6 * u, 15 * u), CornerRadius(2 * u))
    }
}

private fun DrawScope.fry(u: Float, s: Float, t: Float) {
    flames(u, t, 46f, 90f)
    val shake = (s - 0.5f) * 4 * u
    translate(shake, 0f) {
        drawOval(SteelDark, Offset(14 * u, 56 * u), Size(64 * u, 24 * u))
        drawOval(Color(0xFF3A3F45), Offset(18 * u, 58 * u), Size(56 * u, 18 * u))
        drawLine(SteelDark, Offset(76 * u, 66 * u), Offset(94 * u, 60 * u), 5 * u, StrokeCap.Round)
        drawOval(Color.White, Offset(30 * u, 61 * u), Size(24 * u, 11 * u))
        drawCircle(FlameCore, 4 * u, Offset(42 * u, 66 * u))
    }
    // sizzle
    for (i in 0 until 6) {
        val p = (t * 2 + i / 6f) % 1f
        val x = (24 + (i * 37) % 46) * u
        drawCircle(Color.White.copy(alpha = 1 - p), 1.4f * u, Offset(x, (56 - p * 14) * u))
    }
}

private fun DrawScope.bake(u: Float, t: Float, steam: Color) {
    drawRoundRect(SteelDark, Offset(18 * u, 30 * u), Size(64 * u, 54 * u), CornerRadius(6 * u))
    drawRect(Steel, Offset(18 * u, 30 * u), Size(64 * u, 8 * u))
    for (i in 0 until 3) drawCircle(Color(0xFF2A2F35), 2 * u, Offset((30 + i * 10) * u, 34 * u))
    val glow = 0.55f + 0.35f * sin((t * 2 * PI).toFloat())
    drawRoundRect(Flame.copy(alpha = glow), Offset(26 * u, 44 * u), Size(48 * u, 32 * u), CornerRadius(4 * u))
    drawRoundRect(Dough, Offset(34 * u, 62 * u), Size(32 * u, 9 * u), CornerRadius(3 * u))
    steamWisps(u, t, steam, listOf(36f, 50f, 64f), 26f)
}

private fun DrawScope.mix(u: Float, s: Float) {
    // bowl
    drawArc(Steel, 0f, 180f, true, Offset(18 * u, 40 * u), Size(64 * u, 44 * u))
    drawOval(Dough, Offset(22 * u, 56 * u), Size(56 * u, 12 * u))
    val angle = (s - 0.5f) * 50f
    rotate(angle, pivot = Offset(50 * u, 62 * u)) {
        drawLine(Board, Offset(50 * u, 18 * u), Offset(50 * u, 40 * u), 4 * u, StrokeCap.Round)
        for (k in -1..1) drawOval(SteelDark, Offset((44 + k * 3) * u, 38 * u), Size(12 * u - abs(k) * 4 * u, 26 * u), style = Stroke(1.8f * u))
    }
    for (i in 0 until 3) drawCircle(Dough, 2 * u, Offset((30 + i * 20 + s * 4) * u, (52 - s * 6 + i) * u))
}

private fun DrawScope.microwave(u: Float, t: Float) {
    drawRoundRect(Color(0xFFD9DDE1), Offset(12 * u, 30 * u), Size(76 * u, 46 * u), CornerRadius(5 * u))
    drawRoundRect(Color(0xFF263038), Offset(17 * u, 35 * u), Size(50 * u, 36 * u), CornerRadius(3 * u))
    drawRoundRect(FlameCore.copy(alpha = 0.18f), Offset(17 * u, 35 * u), Size(50 * u, 36 * u), CornerRadius(3 * u))
    drawOval(Plate, Offset(24 * u, 60 * u), Size(36 * u, 7 * u))
    val a = (t * 2 * PI).toFloat()
    drawRoundRect(Tomato, Offset((38 + cos(a) * 6) * u, 50 * u), Size(9 * u, 11 * u), CornerRadius(2 * u))
    drawRoundRect(Color(0xFF263038), Offset(70 * u, 37 * u), Size(14 * u, 7 * u), CornerRadius(1.5f * u))
    if (t < 0.5f) drawRect(Color(0xFF6BE38F), Offset(72 * u, 39 * u), Size(10 * u, 3 * u))
    drawCircle(SteelDark, 3 * u, Offset(77 * u, 56 * u))
}

private fun DrawScope.pour(u: Float, t: Float) {
    // bowl
    drawArc(Steel, 0f, 180f, true, Offset(30 * u, 56 * u), Size(52 * u, 34 * u))
    drawOval(Water.copy(alpha = 0.7f), Offset(34 * u, 68 * u), Size(44 * u, 8 * u))
    // tilted jug
    rotate(35f, pivot = Offset(30 * u, 32 * u)) {
        drawRoundRect(Color(0xFFCFE6F5), Offset(14 * u, 16 * u), Size(26 * u, 32 * u), CornerRadius(4 * u))
        drawRect(Water, Offset(14 * u, 30 * u), Size(26 * u, 18 * u))
    }
    // falling stream
    for (i in 0 until 5) {
        val p = (t + i * 0.2f) % 1f
        drawLine(Water, Offset(48 * u, (40 + p * 28) * u), Offset(48 * u, (44 + p * 28) * u), 3 * u, StrokeCap.Round)
    }
}

private fun DrawScope.rest(u: Float, t: Float, steam: Color, accent: Color) {
    val center = Offset(50 * u, 56 * u)
    drawCircle(Plate, 28 * u, center)
    drawCircle(SteelDark, 28 * u, center, style = Stroke(3 * u))
    for (i in 0 until 12) {
        val a = (i * PI / 6).toFloat()
        drawCircle(SteelDark, 1.2f * u, center + Offset(cos(a) * 22 * u, sin(a) * 22 * u))
    }
    val a = (t * 2 * PI - PI / 2).toFloat()
    drawLine(accent, center, center + Offset(cos(a) * 19 * u, sin(a) * 19 * u), 3 * u, StrokeCap.Round)
    drawLine(SteelDark, center, center + Offset(0f, -12 * u), 3.5f * u, StrokeCap.Round)
    drawCircle(SteelDark, 3 * u, center)
    steamWisps(u, t, steam, listOf(40f, 60f), 24f)
}

private fun DrawScope.serve(u: Float, t: Float, steam: Color) {
    drawOval(Plate, Offset(14 * u, 58 * u), Size(72 * u, 22 * u))
    drawOval(Color(0xFFDCD3C4), Offset(22 * u, 61 * u), Size(56 * u, 15 * u))
    drawArc(Sauce, 180f, 180f, true, Offset(30 * u, 50 * u), Size(40 * u, 24 * u))
    drawCircle(Leaf, 3 * u, Offset(44 * u, 56 * u))
    drawCircle(Tomato, 3 * u, Offset(56 * u, 57 * u))
    steamWisps(u, t, steam, listOf(42f, 58f), 46f)
    // twinkles
    for (i in 0 until 3) {
        val p = (t + i / 3f) % 1f
        val alpha = sin((p * PI).toFloat())
        val o = Offset((22 + i * 28) * u, (30 + (i % 2) * 8) * u)
        val r = 4 * u * alpha
        drawLine(FlameCore.copy(alpha = alpha), o - Offset(r, 0f), o + Offset(r, 0f), 1.6f * u, StrokeCap.Round)
        drawLine(FlameCore.copy(alpha = alpha), o - Offset(0f, r), o + Offset(0f, r), 1.6f * u, StrokeCap.Round)
    }
}
