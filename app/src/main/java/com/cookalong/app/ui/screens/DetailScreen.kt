package com.cookalong.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.data.Ingredient
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.Scaling
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
import com.cookalong.app.ui.components.breathingGlow
import com.cookalong.app.ui.components.kenBurns
import com.cookalong.app.ui.components.rememberReduceMotion
import com.cookalong.app.ui.components.staggeredEnter

@Composable
fun DetailScreen(
    recipe: Recipe,
    servings: Int,
    minServings: Int,
    maxServings: Int,
    onServings: (Int) -> Unit,
    onStart: () -> Unit,
    onShowQr: () -> Unit,
) {
    val c = LocalCookColors.current
    val startFocus = remember { FocusRequester() }
    val factor = Scaling.factor(recipe.servings, servings)
    val reduceMotion = rememberReduceMotion()
    val openedAt = remember(recipe.id) { System.currentTimeMillis() }

    Row(
        Modifier.fillMaxSize().cookBackdrop().padding(horizontal = Safe.horizontal, vertical = Safe.vertical),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // ---- Left pane: hero + metadata + primary action
        Column(Modifier.weight(0.46f).fillMaxHeight()) {
            // The photo gives way when a long title or credit needs the room, so the buttons always stay on screen.
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 72.dp, max = 220.dp).clip(RoundedCornerShape(20.dp))) {
                AssetImage(recipe.image, recipe.id, recipe.title, Modifier.fillMaxSize().kenBurns(!reduceMotion), recipe.categories)
                Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipe.dietaryTags.take(3).forEach { TagChip(it) }
                }
                recipe.imageCredit?.takeIf { it.isNotBlank() }?.let { credit ->
                    // Required attribution for Creative Commons photos.
                    Text(
                        "Photo: $credit", style = CookType.label.copy(fontSize = 14.sp), color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                recipe.title, style = CookType.headline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (recipe.description.isNotBlank()) {
                Text(recipe.description, style = CookType.body.copy(fontSize = CookType.label.fontSize * 1.1f), color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            recipe.credit?.let { credit ->
                // Attribution (required for CC BY-SA recipes)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Recipe: $credit" + (recipe.license?.let { " · $it" } ?: ""),
                    style = CookType.label.copy(fontSize = 16.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconLabel(CookIcons.Clock, "Prep ${Scaling.minutesLabel(recipe.prepTime)}")
                IconLabel(CookIcons.Timer, "Cook ${Scaling.minutesLabel(recipe.cookTime)}")
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(
                    text = "Start Cooking", icon = CookIcons.Play, primary = true, large = true, onClick = onStart,
                    modifier = Modifier.breathingGlow(c.accent, !reduceMotion).focusRequester(startFocus),
                    contentDescription = "Start cooking ${recipe.title}, ${recipe.steps.size} steps, for $servings servings",
                )
                PillButton(
                    text = "Shopping list to phone", icon = CookIcons.Qr, onClick = onShowQr,
                    contentDescription = "Show a QR code with the shopping list for your phone",
                )
            }
        }

        // ---- Right pane: servings + ingredients
        Column(Modifier.weight(0.54f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ingredients", style = CookType.headline, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                ServingsStepper(servings, minServings, maxServings, onServings)
            }
            Text(
                if (servings == recipe.servings) "For ${recipe.servings} servings (as written)"
                else "Scaled for $servings servings (recipe is written for ${recipe.servings})",
                style = CookType.label.copy(fontSize = CookType.label.fontSize * 1.1f),
                color = if (servings == recipe.servings) c.textMuted else c.accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(recipe.ingredients, key = { _, it -> it.id }) { i, ing ->
                    IngredientRow(ing, factor, Modifier.staggeredEnter(i, openedAt, !reduceMotion))
                }
                if (recipe.ingredients.any { it.substitutionNotes != null }) {
                    item {
                        Text(
                            "Swap ideas are suggestions only, not allergy advice. Always check labels.",
                            style = CookType.label, color = c.textMuted, modifier = Modifier.padding(top = 10.dp, start = 16.dp),
                        )
                    }
                }
            }
        }
    }

    InitialFocus(startFocus, recipe.id)
}

@Composable
fun ServingsStepper(servings: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val c = LocalCookColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(CookIcons.Minus, "Fewer servings", enabled = servings > min) { onChange(-1) }
        Column(Modifier.width(118.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$servings", style = CookType.headline, color = c.text)
            Text(if (servings == 1) "serving" else "servings", style = CookType.label, color = c.textMuted)
        }
        RoundIconButton(CookIcons.Plus, "More servings", enabled = servings < max) { onChange(+1) }
    }
}

@Composable
fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalCookColors.current
    FocusSurface(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        color = c.surfaceRaised,
        focusedColor = c.text,
        focusedScale = 1.12f,
        contentDescription = if (enabled) description else "$description, unavailable",
        modifier = Modifier.size(56.dp),
    ) { focused ->
        Icon(
            icon, contentDescription = null,
            tint = (if (focused) c.background else c.text).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.align(Alignment.Center).size(30.dp),
        )
    }
}

/** Focusable (not clickable) so the D-pad can scroll the list one ingredient at a time, with a visible marker. */
@Composable
private fun IngredientRow(ing: Ingredient, factor: Double, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    var focused by remember { mutableStateOf(false) }
    val qty = Scaling.formatQuantity(ing, factor)
    val spoken = Scaling.describe(ing, factor) + (ing.substitutionNotes?.let { ". Swap idea: $it" } ?: "")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) c.surfaceRaised else c.surface)
            .border(3.dp, if (focused) c.focusRing else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(6.dp).height(30.dp).clip(RoundedCornerShape(3.dp)).background(if (focused) c.focusRing else c.surface))
        Spacer(Modifier.width(12.dp))
        Text(qty ?: "—", style = CookType.body, color = c.accent, modifier = Modifier.width(150.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(
                if (qty == null) Scaling.unquantifiedName(ing) else ing.name,
                style = CookType.body, color = c.text,
            )
            ing.substitutionNotes?.let {
                Text("Swap idea: $it", style = CookType.label, color = c.textMuted, maxLines = if (focused) 4 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
