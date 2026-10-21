package com.cookalong.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.ai.DetectedItem
import com.cookalong.app.data.MatchTier
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeMatch
import com.cookalong.app.data.RecipeMatcher
import com.cookalong.app.data.Scaling
import com.cookalong.app.ui.ScanUi
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.InitialFocus
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.components.QrCode
import com.cookalong.app.ui.components.Shimmer
import com.cookalong.app.ui.components.TagChip
import com.cookalong.app.ui.components.rememberReduceMotion
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.cookBackdrop
import com.cookalong.app.ui.theme.Safe

/**
 * @param restoreFocus id of the recipe opened from the results, so Back from it lands on the same card.
 */
@Composable
fun FridgeScanScreen(
    scan: ScanUi,
    onOpenRecipe: (Recipe) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    restoreFocus: String? = null,
    /** Opens the dinner-planner agent with what was detected; null hides "Plan dinner". */
    onPlan: (() -> Unit)? = null,
) {
    val c = LocalCookColors.current
    Column(Modifier.fillMaxSize().cookBackdrop().padding(horizontal = Safe.horizontal, vertical = Safe.vertical)) {
        // The results have their own headline, and need the room for the recipe cards.
        if (scan !is ScanUi.Results) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CookIcons.Camera, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fridge scan", style = CookType.display, color = c.text, modifier = Modifier.semantics { heading() })
                    Text("AI spots what's in your fridge and finds recipes you can cook", style = CookType.body, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        AnimatedContent(
            targetState = scan,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "scan",
        ) { s ->
            when (s) {
                ScanUi.Starting -> Shimmer(Modifier.size(320.dp).clip(RoundedCornerShape(20.dp)))
                is ScanUi.WaitingForPhone -> Waiting(s, onBack)
                is ScanUi.Results -> Results(s, restoreFocus, onOpenRecipe, onRetry, onBack, onPlan)
                is ScanUi.Failed -> Problem(s.message, onRetry, onBack)
                ScanUi.NeedsInternet -> Problem(
                    "Fridge scan needs the internet. Connect this TV to Wi-Fi or Ethernet, then try again.", onRetry, onBack,
                )
            }
        }
    }
}

@Composable
private fun Waiting(s: ScanUi.WaitingForPhone, onBack: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(s.link, size = 300.dp)
            Spacer(Modifier.height(10.dp))
            Text("Code ${s.code}", style = CookType.title, color = c.text)
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StepLine(1, "Scan this code with your phone's camera", done = true)
            StepLine(2, "Take one photo of your open fridge or pantry", done = s.analyzing)
            StepLine(3, "Your matching recipes appear right here", done = false)
            Spacer(Modifier.height(4.dp))
            if (s.problem != null) {
                Text(
                    s.problem + " Take another photo on your phone.",
                    style = CookType.body, color = c.alert,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                ThinkingDots()
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        s.offline -> "Lost connection. Reconnecting…"
                        s.analyzing -> "Looking at your photo…"
                        else -> "Waiting for your photo…"
                    },
                    style = CookType.title, color = if (s.offline) c.alert else c.accent,
                )
            }
            PillButton("Cancel", icon = CookIcons.Close, onClick = onBack, modifier = Modifier.focusRequester(focus))
        }
    }
    InitialFocus(focus, s.code)
}

@Composable
internal fun StepLine(n: Int, text: String, done: Boolean) {
    val c = LocalCookColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (done) c.accent else c.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(CookIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(24.dp))
            else Text("$n", style = CookType.title.copy(fontSize = 22.sp), color = c.text)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = CookType.body, color = c.text)
    }
}

/** Three dots that bounce in turn while the AI works. Still when motion is reduced. */
@Composable
internal fun ThinkingDots() {
    val c = LocalCookColors.current
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            val lift = if (reduce) 0f else (1f - kotlin.math.abs(p - i - 0.5f).coerceAtMost(1f)) * 10f
            Box(Modifier.graphicsLayer { translationY = -lift * density }.size(14.dp).clip(CircleShape).background(c.accent))
        }
    }
}

/**
 * What you can cook, grouped: "Ready to cook" (nothing missing, or one side ingredient), "Almost there" (two or three
 * things, or the main ingredient), and "Closest matches" only when neither group has anything. Rows scroll
 * vertically; Up/Down moves between rows, Left/Right along a row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Results(
    s: ScanUi.Results,
    restoreFocus: String?,
    onOpenRecipe: (Recipe) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlan: (() -> Unit)?,
) {
    val usable = remember(s) { RecipeMatcher.usable(s.items.map { it.name }).toSet() }
    if (s.matches.isEmpty()) {
        NoMatches(s, usable, onRetry, onBack)
        return
    }
    val groups = remember(s) { s.matches.groupBy { it.tier } } // keeps the matcher's order inside each group
    val target = s.matches.firstOrNull { it.recipe.id == restoreFocus } ?: s.matches.first()
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Summary(s, usable, onRetry, onPlan)
        // Only the rows scroll; the summary stays put so you always see what was found.
        val rowScrolling = LocalBringIntoViewSpec.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCaptionSpec()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                MatchTier.entries.forEach { tier ->
                    // Rows keep the platform's default "scroll just enough" behaviour sideways.
                    groups[tier]?.let { CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) { MatchRow(tier, it, target.recipe.id, focus, onOpenRecipe) } }
                }
                Spacer(Modifier.height(40.dp)) // room to scroll the last row's captions fully into view
            }
        }
    }
    InitialFocus(focus, s to target.recipe.id)
}

/**
 * Vertical scrolling for the result rows. A card is focusable but its "You have 3 of 6 · Need: …" caption sits below
 * it, outside the focus target, so the default "scroll just enough to show the card" would leave the caption cut
 * off. This reserves room under the card for the caption, and above it for the row title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberCaptionSpec(captionDp: Dp = 74.dp, titleDp: Dp = 48.dp): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, captionDp, titleDp) {
        val caption = with(density) { captionDp.toPx() } // the two-line "You have … Need: …" text under a card
        val title = with(density) { titleDp.toPx() } // the row title above it
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = offset + size + caption
                return when {
                    offset < title -> offset - title
                    end > containerSize -> minOf(end - containerSize, offset - title)
                    else -> 0f
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(s: ScanUi.Results, usable: Set<String>, onRetry: () -> Unit, onPlan: (() -> Unit)?) {
    val c = LocalCookColors.current
    val ready = s.matches.count { it.tier == MatchTier.READY }
    val almost = s.matches.count { it.tier == MatchTier.ALMOST }
    val headline = when {
        ready > 0 -> "You can cook ${count(ready, "recipe")} right now"
        almost > 0 -> "${count(almost, "recipe")} almost ready"
        else -> "Nothing fits yet. Closest matches"
    }
    val staples = s.items.filter { it.name !in usable }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                headline, style = CookType.title, color = c.text,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(4.dp))
            DetectedChips(s.items.filter { it.name in usable }, limit = 8, maxLines = 1)
            if (staples.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Staples like ${staples.joinToString(", ") { it.name }} don't count toward a match",
                    style = CookType.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        if (onPlan != null) {
            PillButton("Plan dinner", icon = CookIcons.List, primary = true, compact = true, onClick = onPlan)
            Spacer(Modifier.width(12.dp))
        }
        PillButton("Scan again", icon = CookIcons.Camera, compact = true, onClick = onRetry)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectedChips(items: List<DetectedItem>, limit: Int = 16, maxLines: Int = 2) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        maxLines = maxLines,
    ) {
        items.take(limit).forEach { TagChip(if (it.confidence == "high") it.name else "${it.name}?") }
        if (items.size > limit) TagChip("+${items.size - limit} more")
    }
}

@Composable
private fun MatchRow(
    tier: MatchTier,
    matches: List<RecipeMatch>,
    targetId: String,
    focus: FocusRequester,
    onOpenRecipe: (Recipe) -> Unit,
) {
    val c = LocalCookColors.current
    val (title, hint) = when (tier) {
        MatchTier.READY -> "Ready to cook" to "You have everything, or all but one thing"
        MatchTier.ALMOST -> "Almost there" to "Pick up two or three things first"
        MatchTier.CLOSEST -> "Closest matches" to "You'll need to shop for these"
    }
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$title · ${matches.size}", style = CookType.title, color = c.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.width(16.dp))
            Text(hint, style = CookType.label, color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        // Start the row at the card to focus, so it is composed (and focusable) straight away.
        val start = matches.indexOfFirst { it.recipe.id == targetId }.coerceAtLeast(0)
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0)),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(matches, key = { it.recipe.id }) { m ->
                MatchCard(m, if (m.recipe.id == targetId) Modifier.focusRequester(focus) else Modifier) { onOpenRecipe(m.recipe) }
            }
        }
    }
}

@Composable
private fun MatchCard(m: RecipeMatch, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCookColors.current
    val need = remember(m) { m.missing.map { RecipeMatcher.label(it.name) } }
    val spoken = remember(m) { describe(m, need) }
    Column(Modifier.width(272.dp)) {
        // Replaces the card's own description, so screen readers hear what you have and what you need.
        RecipeCard(m.recipe, modifier.semantics { contentDescription = spoken }, onClick)
        Spacer(Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                if (need.isEmpty()) {
                    withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)) { append("✓ You have everything") }
                    append(" · nothing to buy")
                } else {
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append("You have ${m.have.size} of ${m.needed}") }
                    append(" · Need: ")
                    append(need.joinToString(", "))
                }
            },
            style = CookType.label, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** What a screen reader says for a match card. */
private fun describe(m: RecipeMatch, need: List<String>): String = buildString {
    append(m.recipe.title).append(". ")
    append(
        when (m.tier) {
            MatchTier.READY -> "Ready to cook. "
            MatchTier.ALMOST -> "Almost there. "
            MatchTier.CLOSEST -> "Close match. "
        },
    )
    if (need.isEmpty()) append("You have everything you need. ")
    else append("You have ${m.have.size} of ${m.needed} main ingredients. Need ").append(need.joinToString(", ")).append(". ")
    append(Scaling.minutesLabel(m.recipe.totalMinutes)).append(" total. Serves ").append(m.recipe.servings).append('.')
}

/** Nothing to show: no food in the photo, only staples, or no recipe close enough. Explains why and what to try. */
@Composable
private fun NoMatches(s: ScanUi.Results, usable: Set<String>, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    val (title, body) = when {
        s.items.isEmpty() -> "I couldn't spot any food" to s.note.ifBlank { "The photo didn't show anything I could cook with." }
        usable.isEmpty() -> "I only spotted kitchen staples" to
            "Salt, oil and spices go into everything, so I need a few fresh ingredients to suggest a recipe."
        else -> "No recipe fits these ingredients yet" to
            "A photo with more of your fridge in it gives me more to work with."
    }
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp).clip(CircleShape).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            Icon(CookIcons.Camera, contentDescription = null, tint = c.accent, modifier = Modifier.size(68.dp))
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = CookType.headline, color = c.text,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(6.dp))
            Text(body, style = CookType.body, color = c.textMuted)
            if (s.items.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                DetectedChips(s.items)
            }
            Spacer(Modifier.height(16.dp))
            Text("For a better scan:", style = CookType.label, color = c.text)
            Spacer(Modifier.height(4.dp))
            listOf(
                "Open the fridge door wide and switch the light on",
                "Step back so every shelf is in the photo",
                "Try the vegetable drawer or a pantry shelf too",
            ).forEach { Text("•  $it", style = CookType.label, color = c.textMuted) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton("Scan again", icon = CookIcons.Camera, primary = true, onClick = onRetry, modifier = Modifier.focusRequester(focus))
                PillButton("Browse recipes", icon = CookIcons.ChevronLeft, onClick = onBack)
            }
        }
    }
    InitialFocus(focus, s)
}

@Composable
private fun Problem(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = CookType.title, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton("Try again", icon = CookIcons.Restart, primary = true, onClick = onRetry, modifier = Modifier.focusRequester(focus))
            PillButton("Back to recipes", icon = CookIcons.ChevronLeft, onClick = onBack)
        }
    }
    InitialFocus(focus, message)
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
