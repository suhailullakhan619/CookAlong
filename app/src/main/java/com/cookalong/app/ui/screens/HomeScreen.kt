package com.cookalong.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.Scaling
import com.cookalong.app.ui.UiState
import com.cookalong.app.ui.components.AssetImage
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.FocusSurface
import com.cookalong.app.ui.components.IconLabel
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.components.TagChip
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.Safe
import com.cookalong.app.ui.components.InitialFocus

/** Which card to put focus back on when returning to Home. */
data class HomeFocus(val row: Int, val recipeId: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    online: Boolean,
    listState: LazyListState,
    // Per-shelf scroll positions live outside Home so they survive Detail/Cooking; otherwise a shelf resets to its
    // first card and focus can't return to the card that was opened.
    rowStates: MutableMap<Int, LazyListState>,
    restoreFocus: HomeFocus?,
    onOpen: (Recipe, HomeFocus) -> Unit,
    onToggleTheme: () -> Unit,
    onFridgeScan: (() -> Unit)? = null,
    onAlexa: (() -> Unit)? = null,
) {
    val c = LocalCookColors.current
    val rows = remember(state.recipes, state.config) {
        state.config.rows
            .map { rule -> rule.title to state.recipes.filter(rule::matches) }
            .filter { it.second.isNotEmpty() }
    }
    val target = restoreFocus?.takeIf { f -> rows.getOrNull(f.row)?.second?.any { it.id == f.recipeId } == true }
        ?: rows.firstOrNull()?.second?.firstOrNull()?.let { HomeFocus(0, it.id) }
    val focusRequester = remember { FocusRequester() }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(start = Safe.horizontal, end = Safe.horizontal, top = Safe.vertical)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CookAlong", style = CookType.display, color = c.accent,
                    modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1,
                )
                if (onFridgeScan != null) {
                    PillButton(
                        text = "Fridge scan", icon = CookIcons.Camera, primary = true, onClick = onFridgeScan,
                        contentDescription = "Fridge scan: photograph your fridge with your phone and get recipe ideas",
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (onAlexa != null) {
                    PillButton(
                        text = if (state.alexaLinked) "Alexa on" else "Connect Alexa", icon = CookIcons.Mic, onClick = onAlexa,
                        contentDescription = if (state.alexaLinked) "Alexa is connected. Open Alexa settings"
                        else "Connect Alexa: control CookAlong with your voice from any Echo",
                    )
                    Spacer(Modifier.width(16.dp))
                }
                PillButton(
                    text = if (c.isDark) "Light theme" else "Dark theme",
                    icon = CookIcons.Contrast,
                    onClick = onToggleTheme,
                    contentDescription = if (c.isDark) "Switch to light theme" else "Switch to dark theme",
                )
            }
            // Same one-line slot as the tagline, so the notice never moves the shelves.
            if (online) Text("Hands-free recipes. Pick one to get started.", style = CookType.body, color = c.textMuted, maxLines = 1)
            else OfflineNotice(offlineNotice(state.onlineRecipes))
        }
    }

    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            header()
            EmptyCatalog()
        }
        return
    }

    val rowScrolling = rememberRowSpec()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(c.background),
            contentPadding = PaddingValues(bottom = Safe.vertical),
        ) {
            item(key = "header") { header() }
            rows.forEachIndexed { rowIndex, (title, recipes) ->
                item(key = "row-$rowIndex") {
                    Column {
                        Text(
                            title, style = CookType.title, color = c.text,
                            modifier = Modifier.padding(start = Safe.horizontal, top = 14.dp).semantics { heading() },
                        )
                        // Rows keep the platform's default "scroll just enough" behaviour horizontally.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                            LazyRow(
                                state = rowStates.getOrPut(rowIndex) { LazyListState() },
                                contentPadding = PaddingValues(horizontal = Safe.horizontal, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                itemsIndexed(recipes, key = { _, r -> r.id }) { _, recipe ->
                                    val isTarget = target?.row == rowIndex && target.recipeId == recipe.id
                                    RecipeCard(
                                        recipe = recipe,
                                        modifier = if (isTarget) Modifier.focusRequester(focusRequester) else Modifier,
                                        onClick = { onOpen(recipe, HomeFocus(rowIndex, recipe.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    InitialFocus(focusRequester, target)
}

/**
 * Sideways scrolling for a shelf: a card that is already fully inside the overscan-safe area never moves the row, so the
 * first card stays put while focus walks right. Only when the focused card would pass the safe edge does the row scroll,
 * by just enough to bring it back in. (Compose's default pins the focused card near 30% of the width, so every Right
 * press slid the whole row and pushed the first card off the left edge.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberRowSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val margin = with(density) { Safe.horizontal.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val start = margin
                val end = containerSize - margin
                return when {
                    offset >= start && offset + size <= end -> 0f
                    size >= end - start || offset < start -> offset - start
                    else -> offset + size - end
                }
            }
        }
    }
}

/**
 * TV "pivot" scrolling for the shelf list: if the focused card is fully inside the overscan-safe area, don't move;
 * otherwise scroll so the card lands just below its row title. Keeps focus in a predictable place on screen.
 */
@Composable
private fun rememberPivotSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val pivot = with(density) { 64.dp.toPx() }
        val safeBottom = with(density) { Safe.vertical.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val fits = offset >= 0f && offset + size <= containerSize - safeBottom
                return if (fits) 0f else offset - pivot
            }
        }
    }
}

@Composable
fun RecipeCard(recipe: Recipe, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalCookColors.current
    val description = buildString {
        append(recipe.title).append(". ")
        append(Scaling.minutesLabel(recipe.totalMinutes)).append(" total. ")
        append("Serves ").append(recipe.servings).append(". ")
        if (recipe.dietaryTags.isNotEmpty()) append(recipe.dietaryTags.joinToString(", ")).append('.')
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(272.dp),
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        focusedColor = c.surfaceRaised,
        focusedScale = 1.08f,
        contentDescription = description,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(153.dp)) {
                AssetImage(recipe.image, recipe.id, recipe.title, Modifier.fillMaxSize(), recipe.categories)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(RoundedCornerShape(50)).background(c.scrim)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    IconLabel(CookIcons.Clock, Scaling.minutesLabel(recipe.totalMinutes), color = c.text)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    recipe.title, style = CookType.title.copy(fontSize = CookType.body.fontSize),
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconLabel(CookIcons.Person, "Serves ${recipe.servings}")
                    Spacer(Modifier.width(14.dp))
                    IconLabel(CookIcons.List, "${recipe.steps.size} steps")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipe.dietaryTags.take(2).forEach { TagChip(it) }
                    if (recipe.dietaryTags.isEmpty()) Spacer(Modifier.size(32.dp))
                }
            }
        }
    }
}

/** Not focusable; announced politely when it appears. */
@Composable
private fun OfflineNotice(text: String) {
    val c = LocalCookColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.alert))
        Spacer(Modifier.width(10.dp))
        Text(text, style = CookType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyCatalog() {
    val c = LocalCookColors.current
    Box(Modifier.fillMaxSize().padding(horizontal = Safe.horizontal, vertical = Safe.vertical), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No recipes yet", style = CookType.headline, color = c.text)
            Spacer(Modifier.height(12.dp))
            Text(
                "This copy of CookAlong doesn't have any recipes installed.",
                style = CookType.body, color = c.textMuted,
            )
        }
    }
}

/** What the Home notice says while offline. With no downloaded recipes yet, it says more will come when online. */
internal fun offlineNotice(onlineRecipes: Int) =
    if (onlineRecipes > 0) "You're offline. Showing saved recipes."
    else "You're offline. More recipes download when you're online."
