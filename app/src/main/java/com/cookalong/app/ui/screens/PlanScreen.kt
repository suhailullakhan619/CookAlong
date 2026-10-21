package com.cookalong.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.ai.PlanMeal
import com.cookalong.app.ai.PlanResult
import com.cookalong.app.data.Recipe
import com.cookalong.app.ui.PlanUi
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.FocusSurface
import com.cookalong.app.ui.components.InitialFocus
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.cookBackdrop
import com.cookalong.app.ui.theme.Safe
import kotlinx.coroutines.delay

private val CardWidth = 272.dp

/** The dinner-planner agent's answer: the summary, up to three meals, a shopping list and a tip. */
@Composable
fun PlanScreen(
    plan: PlanUi,
    findRecipe: (String) -> Recipe?,
    onOpenRecipe: (Recipe) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onShoppingQr: () -> Unit,
    restoreFocus: String? = null,
) {
    val c = LocalCookColors.current
    val side = Modifier.padding(horizontal = Safe.horizontal)
    // No side padding here: the meal row runs edge to edge (its content padding is the overscan margin) so a focused card can grow into it.
    Column(Modifier.fillMaxSize().cookBackdrop().padding(vertical = Safe.vertical)) {
        AnimatedContent(
            targetState = plan,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "plan",
        ) { p ->
            when (p) {
                PlanUi.Loading -> Planning(onBack, side)
                is PlanUi.Done -> {
                    // Only meals this TV has a recipe for can open; the agent works from the shared online catalog.
                    val meals = remember(p) { p.result.plan.meals.mapNotNull { m -> findRecipe(m.recipeId)?.let { m to it } }.take(3) }
                    if (meals.isEmpty()) Problem(
                        p.result.plan.summary.ifBlank { "I couldn't find a dinner for those ingredients. Try a scan with more in it." },
                        onRetry, onBack, side,
                    )
                    else Done(p.result, p.servings, meals, restoreFocus, onOpenRecipe, onRetry, onShoppingQr)
                }
                is PlanUi.Failed -> Problem(p.message, onRetry, onBack, side)
                PlanUi.NeedsInternet -> Problem(
                    "Dinner planning needs the internet. Connect this TV to Wi-Fi or Ethernet, then try again.", onRetry, onBack, side,
                )
            }
        }
    }
}

/** What the agent is doing, in the order it does it. The answer arrives in one piece, so these advance on a timer. */
private val AgentSteps = listOf(
    "Looking through recipes for your ingredients",
    "Checking what you'd still need",
    "Finding swaps for missing items",
    "Writing your shopping list",
)

@Composable
private fun Planning(onBack: () -> Unit, modifier: Modifier) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (step < AgentSteps.lastIndex) {
            delay(2_800)
            step++
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CookIcons.List, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Dinner planner", style = CookType.display, color = c.text, modifier = Modifier.semantics { heading() })
                Text("An AI agent on Amazon Nova is choosing tonight's dinner", style = CookType.body, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentSteps.forEachIndexed { i, text -> StepLine(i + 1, text, done = i < step) }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            ThinkingDots()
            Spacer(Modifier.width(12.dp))
            Text("Planning dinner…", style = CookType.title, color = c.accent)
        }
        Spacer(Modifier.height(20.dp))
        PillButton("Cancel", icon = CookIcons.Close, onClick = onBack, modifier = Modifier.focusRequester(focus))
    }
    InitialFocus(focus, Unit)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Done(
    result: PlanResult,
    servings: Int,
    meals: List<Pair<PlanMeal, Recipe>>,
    restoreFocus: String?,
    onOpenRecipe: (Recipe) -> Unit,
    onRetry: () -> Unit,
    onShoppingQr: () -> Unit,
) {
    val c = LocalCookColors.current
    val plan = result.plan
    val target = meals.firstOrNull { it.second.id == restoreFocus } ?: meals.first()
    val focus = remember { FocusRequester() }
    val rowSpec = rememberRowSpec()
    val side = Modifier.padding(horizontal = Safe.horizontal)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCaptionSpec(captionDp = 96.dp, titleDp = 8.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(side.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tonight's dinner · for ${if (servings == 1) "1 person" else "$servings people"}",
                    style = CookType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Spacer(Modifier.width(16.dp))
                PillButton("Plan again", icon = CookIcons.Restart, compact = true, onClick = onRetry)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                plan.summary, style = CookType.body, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = side.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val start = meals.indexOfFirst { it.second.id == target.second.id }.coerceAtLeast(0)
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0)),
                    contentPadding = PaddingValues(start = Safe.horizontal, end = Safe.horizontal, top = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(meals, key = { it.second.id }) { (meal, recipe) ->
                        MealCard(
                            meal, recipe,
                            if (recipe.id == target.second.id) Modifier.focusRequester(focus) else Modifier,
                        ) { onOpenRecipe(recipe) }
                    }
                }
            }
            ShoppingPanel(plan.shoppingList, plan.tip, onClick = onShoppingQr, modifier = side)
            val trace = remember(result) { result.trace() }
            if (trace.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Planned by an agent on Amazon Nova",
                    style = CookType.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = side.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    InitialFocus(focus, result to target.second.id)
}

@Composable
private fun MealCard(meal: PlanMeal, recipe: Recipe, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCookColors.current
    val spoken = remember(meal) { describe(meal, recipe) }
    Column(Modifier.width(CardWidth)) {
        // Replaces the card's own description: what you have, what you need, and the agent's reason.
        RecipeCard(recipe, modifier.semantics { contentDescription = spoken }, onClick)
        Spacer(Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                if (meal.missing.isEmpty()) {
                    // Short enough for one line under the card; the spoken description says it in full.
                    withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)) { append("✓ Nothing to buy") }
                } else {
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append("${meal.have} of ${meal.total}") }
                    append(" · Need: ")
                    append(meal.missing.joinToString(", "))
                }
            },
            style = CookType.label, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val swaps = remember(meal) { usefulSwaps(meal) }
        if (swaps.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Swap · " + swaps.take(2).joinToString(" · "),
                style = CookType.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** The agent writes "ingredient: none" when it has no swap; that is not worth showing. */
internal fun usefulSwaps(m: PlanMeal): List<String> = m.swaps.filter { sw ->
    val after = sw.substringAfter(':', sw).trim().trimEnd('.').lowercase()
    after.isNotEmpty() && after !in setOf("none", "n/a", "no swap", "no substitute", "no good swap")
}

private fun describe(m: PlanMeal, recipe: Recipe): String = buildString {
    append(recipe.title).append(". ")
    if (m.minutes > 0) append(m.minutes).append(" minutes. ")
    if (m.missing.isEmpty()) append("You have everything you need. ")
    else append("You have ${m.have} of ${m.total} ingredients. Need ").append(m.missing.joinToString(", ")).append(". ")
    usefulSwaps(m).let { if (it.isNotEmpty()) append("Swaps: ").append(it.take(2).joinToString(". ")).append(". ") }
    if (m.why.isNotBlank()) append(m.why)
}

/** The shopping list and tip in one panel. Focusable so Down scrolls it into view; Select shows a QR code for the phone. */
@Composable
private fun ShoppingPanel(items: List<String>, tip: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    if (items.isEmpty() && tip.isBlank()) return
    val shown = items.take(12)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        focusedColor = c.surfaceRaised,
        focusedScale = 1.02f,
        contentDescription = buildString {
            if (items.isNotEmpty()) append("Shopping list: ").append(items.joinToString(", ")).append(". Select for a code to scan with your phone. ")
            if (tip.isNotBlank()) append("Tip: ").append(tip)
        },
    ) { focused ->
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (items.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(CookIcons.List, contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Shopping list · ${items.size}", style = CookType.title, color = c.text, modifier = Modifier.weight(1f))
                    Icon(CookIcons.Qr, contentDescription = null, tint = if (focused) c.accent else c.textMuted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Select for your phone", style = CookType.label, color = c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    shown.joinToString("  ·  ") + if (items.size > shown.size) "  ·  +${items.size - shown.size} more" else "",
                    style = CookType.label, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            if (tip.isNotBlank()) {
                if (items.isNotEmpty()) Spacer(Modifier.height(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)) { append("Tip  ") }
                        append(tip)
                    },
                    style = CookType.label, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Problem(message: String, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            Icon(CookIcons.List, contentDescription = null, tint = c.accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            message, style = CookType.title, color = c.text, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton("Try again", icon = CookIcons.Restart, primary = true, onClick = onRetry, modifier = Modifier.focusRequester(focus))
            PillButton("Back", icon = CookIcons.ChevronLeft, onClick = onBack)
        }
    }
    InitialFocus(focus, message)
}
