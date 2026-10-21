package com.cookalong.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.ui.AlexaUi
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.InitialFocus
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.components.Shimmer
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.cookBackdrop
import com.cookalong.app.ui.theme.Safe

/** Connect Alexa: say the code once, then any Echo (or the remote's Alexa button) can drive CookAlong. */
@Composable
fun AlexaScreen(alexa: AlexaUi, offline: Boolean, onRetry: () -> Unit, onDone: () -> Unit, onDisconnect: () -> Unit) {
    val c = LocalCookColors.current
    Column(Modifier.fillMaxSize().cookBackdrop().padding(horizontal = Safe.horizontal, vertical = Safe.vertical)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CookIcons.Mic, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Cook with Alexa", style = CookType.display, color = c.text, modifier = Modifier.semantics { heading() })
                Text("Use any Echo in your kitchen, or the Alexa button on your remote", style = CookType.body, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = alexa,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "alexa",
        ) { s ->
            when (s) {
                AlexaUi.Starting -> Shimmer(Modifier.size(520.dp, 160.dp).clip(RoundedCornerShape(20.dp)))
                is AlexaUi.Code -> PairCode(s.code, offline, onDone)
                AlexaUi.Linked -> Linked(onDone, onDisconnect)
                is AlexaUi.Failed -> Failed(s.message, "Get a new code", onRetry, onDone)
                AlexaUi.NeedsInternet -> Failed(
                    "Connecting Alexa needs the internet. Connect this TV to Wi-Fi or Ethernet, then try again.",
                    "Try again", onRetry, onDone,
                )
            }
        }
    }
}

@Composable
private fun PairCode(code: String, offline: Boolean, onCancel: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    val spaced = code.toCharArray().joinToString(" ")
    Column {
        Text("Say to Alexa:", style = CookType.title, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        Text(
            "“Alexa, tell Cook Along my code is $spaced”",
            style = CookType.headline, color = c.text,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Pairing code $spaced"
        }) {
            code.forEach { digit ->
                Box(
                    Modifier.size(width = 84.dp, height = 104.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$digit", style = CookType.display.copy(fontSize = 64.sp, fontWeight = FontWeight.Bold), color = c.accent)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (offline) "Lost connection. Reconnecting…" else "The code works for 10 minutes. Waiting for Alexa…",
            style = CookType.body, color = if (offline) c.alert else c.textMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Cancel", icon = CookIcons.Close, onClick = onCancel, modifier = Modifier.focusRequester(focus))
    }
    InitialFocus(focus, code)
}

private val examples = listOf(
    "Alexa, tell Cook Along next step",
    "Alexa, tell Cook Along to set a timer for 5 minutes",
    "Alexa, ask Cook Along how much rice I need",
    "Alexa, ask Cook Along what I can use instead of cream",
    "Alexa, tell Cook Along to find paneer recipes",
)

@Composable
private fun Linked(onDone: () -> Unit, onDisconnect: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(CookIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text("Alexa is connected to this TV", style = CookType.headline, color = c.text)
        }
        Spacer(Modifier.height(22.dp))
        Text("While Cook Along is on screen, try:", style = CookType.title, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        examples.forEach {
            Text("“$it”", style = CookType.body, color = c.text, modifier = Modifier.padding(vertical = 4.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton("Done", icon = CookIcons.Check, primary = true, onClick = onDone, modifier = Modifier.focusRequester(focus))
            PillButton("Disconnect Alexa", icon = CookIcons.Close, onClick = onDisconnect)
        }
    }
    InitialFocus(focus, "linked")
}

@Composable
private fun Failed(message: String, retryLabel: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = LocalCookColors.current
    val focus = remember { FocusRequester() }
    Column(Modifier.padding(top = 20.dp)) {
        Text(message, style = CookType.title, color = c.text)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(retryLabel, icon = CookIcons.Restart, primary = true, onClick = onRetry, modifier = Modifier.focusRequester(focus))
            PillButton("Back to recipes", icon = CookIcons.ChevronLeft, onClick = onBack)
        }
    }
    InitialFocus(focus, message)
}
