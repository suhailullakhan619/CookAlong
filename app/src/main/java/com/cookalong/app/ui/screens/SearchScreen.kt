package com.cookalong.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.data.Recipe
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.InitialFocus
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.cookBackdrop
import com.cookalong.app.ui.theme.Safe

/** Results of a voice search ("Alexa, tell Cook Along to find paneer recipes"). */
@Composable
fun SearchScreen(title: String, recipes: List<Recipe>, onOpen: (Recipe) -> Unit, onBack: () -> Unit) {
    val c = LocalCookColors.current
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().cookBackdrop().padding(vertical = Safe.vertical)) {
        Row(Modifier.padding(horizontal = Safe.horizontal), verticalAlignment = Alignment.CenterVertically) {
            Icon(CookIcons.Mic, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // The title echoes what was said, so a long spoken query must not push the cards off screen.
                Text(
                    title, style = CookType.display, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    when (recipes.size) {
                        0 -> "Nothing yet. Try another dish or ingredient."
                        1 -> "1 recipe"
                        else -> "${recipes.size} recipes"
                    },
                    style = CookType.body, color = c.textMuted,
                )
            }
        }
        if (recipes.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            PillButton(
                "Back to recipes", icon = CookIcons.ChevronLeft, onClick = onBack,
                modifier = Modifier.padding(horizontal = Safe.horizontal).focusRequester(first),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Safe.horizontal, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(recipes, key = { _, r -> r.id }) { i, r ->
                    RecipeCard(r, if (i == 0) Modifier.focusRequester(first) else Modifier) { onOpen(r) }
                }
            }
            Text(
                "Open one, then say “Alexa, tell Cook Along start cooking”",
                style = CookType.body, color = c.textMuted, modifier = Modifier.padding(horizontal = Safe.horizontal),
            )
        }
    }
    InitialFocus(first, title)
}
