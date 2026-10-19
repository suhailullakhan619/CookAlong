package com.cookalong.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.ui.ChefNote
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors

/**
 * "Ask the chef" answer, shown on the TV while Alexa says it (multi-modal: hear it and read it). Not focusable,
 * so it never steals the D-pad; it fades out on its own.
 */
@Composable
fun ChefCard(note: ChefNote?, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    var last by remember { mutableStateOf(note) }
    if (note != null) last = note
    AnimatedVisibility(
        visible = note != null, modifier = modifier,
        enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 }, exit = fadeOut(tween(400)),
    ) {
        val shown = last ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(max = 760.dp).shadow(16.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(c.surfaceRaised).padding(horizontal = 24.dp, vertical = 18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(CookIcons.Mic, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                if (shown.question.isNotBlank()) {
                    Text("You asked: ${shown.question}", style = CookType.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
                Text(shown.answer, style = CookType.body, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
