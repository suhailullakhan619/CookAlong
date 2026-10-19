package com.cookalong.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * True when the TV's "Remove animations" / animator-scale-0 accessibility setting is on. All purely decorative
 * motion in CookAlong checks this and stays still.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Slow "Ken Burns" drift for hero photos: a gentle zoom and pan over ~18 s, back and forth. */
fun Modifier.kenBurns(enabled: Boolean): Modifier = if (!enabled) this else composed {
    val t = rememberInfiniteTransition(label = "kenburns")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    graphicsLayer {
        val s = 1.04f + 0.08f * p
        scaleX = s
        scaleY = s
        translationX = (p - 0.5f) * size.width * 0.04f
        translationY = (0.5f - p) * size.height * 0.03f
    }
}

/** A soft glow that breathes behind a button, to draw the eye to the main action. */
fun Modifier.breathingGlow(color: Color, enabled: Boolean, radius: Dp = 40.dp): Modifier = if (!enabled) this else composed {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(0.15f, 0.45f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "a")
    drawBehind {
        val spread = 14.dp.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = a), Color.Transparent),
                center = center, radius = size.maxDimension * 0.75f,
            ),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + 2 * spread, size.height + 2 * spread),
            cornerRadius = CornerRadius(radius.toPx()),
        )
    }
}

/** A band of light that slowly travels around a rounded border (used on the focused step panel). */
fun Modifier.sweepBorder(enabled: Boolean, colors: List<Color>, corner: Dp, width: Dp = 4.dp): Modifier =
    if (!enabled) this else composed {
        val t = rememberInfiniteTransition(label = "sweep")
        val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "angle")
        drawWithContent {
            drawContent()
            val stroke = width.toPx()
            val brush = Brush.sweepGradient(colors.rotated(angle), center)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(corner.toPx()),
                style = Stroke(stroke),
            )
        }
    }

/** Shifts a gradient's colour stops so the bright band appears to travel around the border. */
private fun List<Color>.rotated(angle: Float): List<Color> {
    if (isEmpty()) return this
    val steps = 36
    return List(steps + 1) { i ->
        val pos = ((i.toFloat() / steps) - angle / 360f + 1f) % 1f
        val idx = pos * (size - 1)
        val lo = idx.toInt().coerceIn(0, size - 1)
        val hi = (lo + 1).coerceAtMost(size - 1)
        androidx.compose.ui.graphics.lerp(this[lo], this[hi], idx - lo)
    }
}

/**
 * Fades and slides an item in once, [index] × 45 ms after [startedAtMs] (when the screen opened). Items that scroll
 * into view later appear immediately, so lazy lists don't replay the animation.
 */
fun Modifier.staggeredEnter(index: Int, startedAtMs: Long, enabled: Boolean): Modifier = if (!enabled) this else composed {
    val due = startedAtMs + index * 45L
    val anim = remember { Animatable(if (System.currentTimeMillis() > due + 250) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            delay((due - System.currentTimeMillis()).coerceAtLeast(0))
            anim.animateTo(1f, tween(320))
        }
    }
    graphicsLayer {
        alpha = anim.value
        translationX = (1f - anim.value) * 40.dp.toPx()
    }
}

/** Little "pop" whenever [key] changes (e.g. the step number). */
fun Modifier.popOnChange(key: Any, enabled: Boolean): Modifier = if (!enabled) this else composed {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(key) {
        scale.snapTo(1.28f)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** Side-to-side wiggle while [active], e.g. a timer in its last seconds. */
fun Modifier.wiggle(active: Boolean): Modifier = if (!active) this else composed {
    val t = rememberInfiniteTransition(label = "wiggle")
    val x by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(110), RepeatMode.Reverse), label = "x")
    graphicsLayer { translationX = x * 3.dp.toPx(); rotationZ = x * 0.6f }
}

/** A moving highlight across a progress segment. */
fun Modifier.shimmerSweep(enabled: Boolean, highlight: Color): Modifier = if (!enabled) this else composed {
    val t = rememberInfiniteTransition(label = "seg-shimmer")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "x")
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, highlight, Color.Transparent),
                startX = (x - 0.25f) * w, endX = (x + 0.25f) * w,
            ),
        )
    }
}

private class Confetto(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var angle: Float, val spin: Float, val w: Float, val h: Float, val color: Color,
)

/**
 * Celebration for finishing a recipe: confetti bursting from two sides plus a check mark drawing itself.
 * Decorative only (hidden from screen readers); when motion is reduced, just the finished check is shown.
 */
@Composable
fun Celebration(colors: List<Color>, checkColor: Color, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val pieces = remember {
        val r = Random(7)
        List(90) { i ->
            val fromLeft = i % 2 == 0
            Confetto(
                x = if (fromLeft) 0f else 1f, y = 0.85f,
                vx = (if (fromLeft) 1 else -1) * (0.25f + r.nextFloat() * 0.45f),
                vy = -(0.9f + r.nextFloat() * 0.7f),
                angle = r.nextFloat() * 360f, spin = (r.nextFloat() - 0.5f) * 720f,
                w = 8f + r.nextFloat() * 8f, h = 4f + r.nextFloat() * 5f, color = colors[i % colors.size],
            )
        }
    }
    var frame by remember { mutableLongStateOf(0L) }
    val check = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        check.animateTo(1f, tween(650, delayMillis = 150))
    }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        var last = 0L
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                pieces.forEach { p ->
                    p.vy += 1.1f * dt            // gravity (screen heights per second²)
                    p.vx *= (1f - 0.6f * dt)     // air drag
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.angle += p.spin * dt
                }
                frame = now
            }
            if ((frame - start) / 1e9f > 4.5f) break
        }
    }
    Box(modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame // read so the canvas redraws every frame
            if (!reduceMotion) {
                pieces.forEach { p ->
                    val px = p.x * size.width
                    val py = p.y * size.height
                    if (py < size.height + 40) {
                        rotate(p.angle, Offset(px, py)) {
                            drawRect(p.color, Offset(px - p.w, py - p.h), Size(p.w * 2, p.h * 2))
                        }
                    }
                }
            }
            // check mark in a circle, drawn progressively
            val r = size.minDimension * 0.11f
            val c = Offset(size.width / 2, size.height * 0.36f)
            val sweep = 360f * check.value.coerceIn(0f, 1f)
            drawArc(checkColor, -90f, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(r * 0.16f, cap = StrokeCap.Round))
            val tick = Path().apply {
                moveTo(c.x - r * 0.45f, c.y + r * 0.02f)
                lineTo(c.x - r * 0.1f, c.y + r * 0.36f)
                lineTo(c.x + r * 0.5f, c.y - r * 0.32f)
            }
            val measure = PathMeasure().apply { setPath(tick, false) }
            val part = Path()
            val drawn = ((check.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (drawn > 0f) {
                measure.getSegment(0f, measure.length * drawn, part, true)
                drawPath(part, checkColor, style = Stroke(r * 0.18f, cap = StrokeCap.Round))
            }
            // soft rays behind the check
            if (!reduceMotion && check.value >= 1f) {
                val phase = (frame / 1e9f) % 1f
                for (i in 0 until 12) {
                    val a = (i * 30f + phase * 30f) * (PI / 180f).toFloat()
                    drawLine(
                        checkColor.copy(alpha = 0.35f),
                        Offset(c.x + cos(a) * r * 1.3f, c.y + sin(a) * r * 1.3f),
                        Offset(c.x + cos(a) * r * 1.7f, c.y + sin(a) * r * 1.7f),
                        strokeWidth = r * 0.08f, cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
