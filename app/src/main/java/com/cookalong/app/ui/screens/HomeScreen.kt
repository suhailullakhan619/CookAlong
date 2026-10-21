package com.cookalong.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.cookalong.app.ui.theme.cookBackdrop
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

    // The billboard follows focus, like a streaming app: whatever card you're on is shown big at the top.
    val featured = remember(state.recipes) { featuredRecipe(state) }
    var spotlight by remember(featured) { mutableStateOf(featured) }

    val nav: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(start = Safe.horizontal, end = Safe.horizontal, top = Safe.vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CookAlong", style = CookType.display, color = c.accent, maxLines = 1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            if (onFridgeScan != null) {
                PillButton(
                    text = "Fridge scan", icon = CookIcons.Camera, primary = true, compact = true, onClick = onFridgeScan,
                    contentDescription = "Fridge scan: photograph your fridge with your phone and get recipe ideas",
                )
                Spacer(Modifier.width(12.dp))
            }
            if (onAlexa != null) {
                PillButton(
                    text = if (state.alexaLinked) "Alexa on" else "Connect Alexa", icon = CookIcons.Mic, compact = true,
                    onClick = onAlexa,
                    contentDescription = if (state.alexaLinked) "Alexa is connected. Open Alexa settings"
                    else "Connect Alexa: control CookAlong with your voice from any Echo",
                )
            }
        }
    }

    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().cookBackdrop()) {
            nav()
            EmptyCatalog()
        }
        return
    }

    val rowScrolling = rememberRowSpec()
    Box(Modifier.fillMaxSize().background(c.background)) {
        spotlight?.let { Billboard(it) }
        Column(Modifier.fillMaxSize()) {
            nav()
            spotlight?.let { SpotlightInfo(it, isFeatured = it.id == featured?.id, offline = if (online) null else offlineNotice(state.onlineRecipes)) }
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec()) {
                // A short fade at the top so rows scrolling up slip under the billboard text instead of being cut off.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(0f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent, endY = ROW_FADE.toPx()),
                                blendMode = BlendMode.DstOut,
                            )
                        },
                    contentPadding = PaddingValues(top = ROW_FADE, bottom = Safe.vertical),
                ) {
                    rows.forEachIndexed { rowIndex, (title, recipes) ->
                        item(key = "row-$rowIndex") {
                            Column {
                                Row(
                                    Modifier.padding(start = Safe.horizontal).height(ROW_TITLE).semantics { heading() },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(50)).background(c.accent))
                                    Spacer(Modifier.width(10.dp))
                                    Text(title, style = CookType.title.copy(fontSize = 20.sp), color = c.text)
                                }
                                // Rows keep the platform's default "scroll just enough" behaviour horizontally.
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                                    LazyRow(
                                        state = rowStates.getOrPut(rowIndex) { LazyListState() },
                                        contentPadding = PaddingValues(horizontal = Safe.horizontal, vertical = CARD_GAP),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    ) {
                                        itemsIndexed(recipes, key = { _, r -> r.id }) { _, recipe ->
                                            val isTarget = target?.row == rowIndex && target.recipeId == recipe.id
                                            PosterCard(
                                                recipe = recipe,
                                                modifier = if (isTarget) Modifier.focusRequester(focusRequester) else Modifier,
                                                onFocus = { spotlight = recipe },
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
 * TV "pivot" scrolling for the shelf list: the focused row always snaps to the same place, its title just below the top
 * fade, like a streaming app. Never stopping part-way means no sliver of the row above is left showing.
 */
@Composable
private fun rememberPivotSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        // Card top = fade + title + the row's top padding, so the title is never under the fade.
        val pivot = with(density) { (ROW_FADE + ROW_TITLE + CARD_GAP).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivot
        }
    }
}

/** Height of the fade at the top of the shelf list, a shelf title, and the space above and below a row of cards. */
private val ROW_FADE = 28.dp
private val ROW_TITLE = 36.dp
private val CARD_GAP = 14.dp

/** "Tonight's pick": changes daily, always a recipe that has a photo. */
internal fun featuredRecipe(state: UiState): Recipe? {
    val withPhoto = state.recipes.filter { it.image != null }.ifEmpty { state.recipes }
    if (withPhoto.isEmpty()) return null
    return withPhoto[(System.currentTimeMillis() / 86_400_000L % withPhoto.size).toInt()]
}

/** Full-bleed photo behind the top of Home, faded into the background on the left and bottom so text stays readable. */
@Composable
private fun Billboard(recipe: Recipe) {
    val c = LocalCookColors.current
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        Crossfade(recipe, animationSpec = tween(400), label = "billboard", modifier = Modifier.fillMaxSize()) { r ->
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(0.32f))
                AssetImage(r.image, r.id, r.title, Modifier.weight(0.68f).fillMaxSize(), r.categories)
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0.30f to c.background, 0.50f to c.background.copy(alpha = 0.75f),
                    0.75f to c.background.copy(alpha = 0.15f), 1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to c.background.copy(alpha = 0.55f), 0.25f to Color.Transparent,
                    0.6f to Color.Transparent, 1f to c.background,
                ),
            ),
        )
    }
}

/** Title, facts and blurb of the recipe in the billboard. Not focusable: the cards below drive it. */
@Composable
private fun SpotlightInfo(recipe: Recipe, isFeatured: Boolean, offline: String?) {
    val c = LocalCookColors.current
    Column(
        Modifier.padding(start = Safe.horizontal, top = 18.dp).width(640.dp).height(186.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // Offline takes the label's slot, so the notice never moves the layout or crowds the top buttons.
        if (offline != null) OfflineNotice(offline)
        else Text(
            if (isFeatured) "TONIGHT'S PICK" else recipe.categories.firstOrNull()?.uppercase() ?: "RECIPE",
            style = CookType.label.copy(letterSpacing = 2.sp), color = c.accent, maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            recipe.title, style = if (recipe.title.length > 22) CookType.hero.copy(fontSize = 44.sp) else CookType.hero,
            color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconLabel(CookIcons.Clock, Scaling.minutesLabel(recipe.totalMinutes), color = c.text)
            Spacer(Modifier.width(18.dp))
            IconLabel(CookIcons.Person, "Serves ${recipe.servings}", color = c.text)
            Spacer(Modifier.width(18.dp))
            IconLabel(CookIcons.List, "${recipe.steps.size} steps", color = c.text)
            recipe.dietaryTags.firstOrNull()?.let {
                Spacer(Modifier.width(16.dp))
                TagChip(it)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            recipe.description, style = CookType.body.copy(fontSize = 18.sp, lineHeight = 26.sp), color = c.textMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Streaming-style landscape poster: photo with the title over a dark fade, time badge in the corner. */
@Composable
private fun PosterCard(recipe: Recipe, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = LocalCookColors.current
    val description = buildString {
        append(recipe.title).append(". ")
        append(Scaling.minutesLabel(recipe.totalMinutes)).append(" total. ")
        append("Serves ").append(recipe.servings).append(". ")
        if (recipe.dietaryTags.isNotEmpty()) append(recipe.dietaryTags.joinToString(", ")).append('.')
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(232.dp).height(130.dp),
        shape = RoundedCornerShape(14.dp),
        color = c.surface,
        focusedScale = 1.12f,
        contentDescription = description,
        onFocusChange = { if (it) onFocus() },
    ) {
        AssetImage(recipe.image, recipe.id, recipe.title, Modifier.fillMaxSize(), recipe.categories)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xB3000000))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(Scaling.minutesLabel(recipe.totalMinutes), style = CookType.label.copy(fontSize = 13.sp), color = Color.White)
        }
        Text(
            recipe.title, style = CookType.label.copy(fontSize = 16.sp), color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000)))),
                )
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
        Text(text, style = CookType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
