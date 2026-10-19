package com.cookalong.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.data.Scaling
import com.cookalong.app.ui.Toast
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first

/**
 * Gives [requester] the initial D-pad focus when the screen appears (and again whenever [key] changes).
 * On a cold start the app window isn't focusable for a moment, and lazy rows attach their items a frame or two
 * late, so this waits for window focus and then retries for up to ~5 seconds.
 */
@Composable
fun InitialFocus(requester: FocusRequester, key: Any?, waitForWindowFocus: Boolean = true) {
    val window = LocalWindowInfo.current
    LaunchedEffect(key) {
        // Dialogs pass false: they live in their own window, while this composition's window loses focus to them.
        if (waitForWindowFocus) snapshotFlow { window.isWindowFocused }.first { it }
        repeat(300) {
            awaitFrame()
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/** Focus animation timing: fast enough to feel responsive, slow enough for the eye to follow (brief: 150–200 ms). */
const val FOCUS_ANIM_MS = 180

/**
 * The single focusable building block. Focus is shown three ways at once (never colour alone): scale-up,
 * elevation, and a high-contrast ring. Select/Enter/D-pad-center trigger [onClick].
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = LocalCookColors.current.surface,
    focusedColor: Color = color,
    focusedScale: Float = 1.06f,
    contentDescription: String? = null,
    onFocusChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val c = LocalCookColors.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(FOCUS_ANIM_MS), label = "scale")
    val elevation by animateDpAsState(if (focused) 18.dp else 0.dp, tween(FOCUS_ANIM_MS), label = "elev")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_ANIM_MS), label = "ring")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, clip = false, ambientColor = c.accent, spotColor = c.accent)
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .border(4.dp, c.focusRing.copy(alpha = ring), shape)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

/** A pill-shaped button with an optional icon. [primary] uses the accent fill. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    large: Boolean = false,
    compact: Boolean = false,
    contentDescription: String? = null,
) {
    val c = LocalCookColors.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (primary) c.accent else c.surfaceRaised,
        focusedColor = if (primary) c.accent else c.text,
        focusedScale = 1.08f,
        contentDescription = contentDescription ?: text,
    ) { focused ->
        val fg = when {
            primary -> c.onAccent
            focused -> c.background
            else -> c.text
        }
        Row(
            Modifier.padding(
                horizontal = if (large) 36.dp else if (compact) 18.dp else 24.dp,
                vertical = if (large) 16.dp else if (compact) 8.dp else 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (large) 32.dp else 24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text, color = fg, maxLines = 1,
                style = if (large) CookType.title else CookType.label.copy(fontSize = if (compact) 19.sp else 20.sp),
            )
        }
    }
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val c = LocalCookColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) c.accent else c.chip)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(text, style = CookType.label, color = if (highlighted) c.onAccent else c.onChip, maxLines = 1)
    }
}

@Composable
fun IconLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = LocalCookColors.current.textMuted) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = CookType.label.copy(fontSize = 20.sp), color = color, maxLines = 1)
    }
}

// ---- Images --------------------------------------------------------------------------------------------------------

private sealed interface ImgState {
    data object Loading : ImgState
    data class Ready(val bitmap: ImageBitmap) : ImgState
    data object Missing : ImgState
}

/**
 * A recipe's picture: a bundled file in `assets/` (e.g. `images/foo.jpg`) or an https URL (online recipes).
 * Shows a shimmer while loading, crossfades in, and falls back to [DishArt] if there is no picture or it can't be
 * loaded (offline before it was ever cached, or a broken file), so a card never shows a broken image.
 */
/** Whether the TV is online; remote photos skip the network while it isn't and reload when it returns. */
val LocalOnline = compositionLocalOf { true }

@Composable
fun AssetImage(
    path: String?,
    seed: String,
    title: String,
    modifier: Modifier = Modifier,
    categories: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val online = LocalOnline.current
    var state by remember(path) {
        mutableStateOf(
            when {
                path.isNullOrBlank() -> ImgState.Missing
                else -> RecipeImages.cached(path)?.let { ImgState.Ready(it) }
                    ?: if (RecipeImages.failedRecently(path)) ImgState.Missing else ImgState.Loading
            },
        )
    }
    LaunchedEffect(path, online) {
        if (path == null) return@LaunchedEffect
        // Back online: a picture that failed (or was skipped) while offline gets another try.
        if (online && state == ImgState.Missing && RecipeImages.isRemote(path)) {
            RecipeImages.forgetFailure(path)
            state = ImgState.Loading
        }
        if (state != ImgState.Loading) return@LaunchedEffect
        state = RecipeImages.load(context, path, online)?.let { ImgState.Ready(it) } ?: ImgState.Missing
    }
    Crossfade(state, animationSpec = tween(300), modifier = modifier, label = "img") { s ->
        when (s) {
            ImgState.Loading -> Shimmer(Modifier.fillMaxSize())
            is ImgState.Ready -> Image(s.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            ImgState.Missing -> DishArt(seed, title, categories, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "x")
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(c.shimmerBase, c.shimmerHighlight, c.shimmerBase),
                start = Offset(x, 0f), end = Offset(x + 400f, 200f),
            ),
        ),
    )
}

// ---- QR ------------------------------------------------------------------------------------------------------------

private fun encodeQr(text: String): ImageBitmap {
    val hints = buildMap<EncodeHintType, Any> {
        put(EncodeHintType.MARGIN, 2)
        // Medium correction tolerates TV glare and moire. The UTF-8 header is added only when the text needs it,
        // because some phone scanners mis-handle it.
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        if (text.any { it.code > 127 }) put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = m.width
    val h = m.height
    val pixels = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Always black-on-white (both themes) with a quiet zone, rendered with nearest-neighbour scaling to stay crisp. */
@Composable
fun QrCode(payload: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { runCatching { encodeQr(payload) }.getOrNull() }
    if (bitmap == null) {
        Box(modifier.size(size).background(LocalCookColors.current.surfaceRaised), contentAlignment = Alignment.Center) {
            Text("This list is too long for a QR code.", style = CookType.body, textAlign = TextAlign.Center, color = LocalCookColors.current.text)
        }
    } else {
        Image(
            bitmap, contentDescription = "QR code with the shopping list",
            filterQuality = FilterQuality.None,
            modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
        )
    }
}

// ---- Timer ring ----------------------------------------------------------------------------------------------------

@Composable
fun CountdownRing(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, stroke: Dp = 6.dp) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
    }
}

// ---- Toasts --------------------------------------------------------------------------------------------------------

/** Corner, non-blocking confirmations. Announced politely to screen readers (VoiceView). */
@Composable
fun ToastHost(toasts: List<Toast>, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        toasts.forEach { t ->
            key(t.id) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visible, enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 3 }) {
                    Row(
                        Modifier
                            .widthIn(max = 460.dp)
                            .shadow(12.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (t.alert) c.alert else c.surfaceRaised)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t.alert) {
                            Icon(CookIcons.Timer, null, tint = c.onAlert, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            t.text, style = CookType.label.copy(fontSize = 21.sp),
                            color = if (t.alert) c.onAlert else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A dot used in segmented progress / status. */
@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

fun durationLabel(seconds: Int): String = Scaling.formatDuration(seconds.toLong())
