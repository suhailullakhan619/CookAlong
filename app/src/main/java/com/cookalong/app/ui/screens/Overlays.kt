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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.Scaling
import com.cookalong.app.data.ShoppingQr
import com.cookalong.app.data.TimerStatus
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.components.QrCode
import com.cookalong.app.ui.components.Shimmer
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.Safe
import com.cookalong.app.ui.components.InitialFocus

/**
 * Overlays are real windows ([Dialog]) so D-pad focus is trapped inside them and Back dismisses them, without any
 * hand-rolled focus fencing. [onDismiss] is the Back key.
 */
@Composable
private fun OverlayWindow(onDismiss: () -> Unit, fullScreen: Boolean, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !fullScreen),
    ) { content() }
}

@Composable
private fun requestOnShow(): FocusRequester {
    val fr = remember { FocusRequester() }
    InitialFocus(fr, Unit, waitForWindowFocus = false)
    return fr
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalCookColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .padding(horizontal = 40.dp, vertical = 30.dp),
    ) { content() }
}

/** Full-screen and unambiguous. "Stay in recipe" is pre-focused as the safe default. */
@Composable
fun ConfirmExitOverlay(recipe: Recipe, stepIndex: Int, timers: List<KitchenTimer>, onStay: () -> Unit, onLeave: () -> Unit) {
    val c = LocalCookColors.current
    val stay = requestOnShow()
    val activeTimers = timers.count { it.status != TimerStatus.COMPLETED }
    OverlayWindow(onDismiss = onStay, fullScreen = true) {
        Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = Safe.horizontal)) {
                Text("Leave cooking mode?", style = CookType.display, color = c.text, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(16.dp))
                Text(
                    buildString {
                        append("You're on step ${stepIndex + 1} of ${recipe.steps.size} of ${recipe.title}")
                        if (activeTimers > 0) append(" with $activeTimers ${if (activeTimers == 1) "timer" else "timers"} going")
                        append(". Leaving resets your progress")
                        if (activeTimers > 0) append(" and cancels the timers")
                        append('.')
                    },
                    style = CookType.body, color = c.textMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    PillButton("Stay in recipe", onClick = onStay, primary = true, large = true, icon = CookIcons.Check, modifier = Modifier.focusRequester(stay))
                    PillButton("Leave", onClick = onLeave, large = true, icon = CookIcons.Close, contentDescription = "Leave cooking mode and lose progress")
                }
            }
        }
    }
}

@Composable
fun TimerControlsOverlay(
    timer: KitchenTimer,
    nowMs: Long,
    onToggle: () -> Unit,
    onAddMinute: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalCookColors.current
    val first = requestOnShow()
    val done = timer.status == TimerStatus.COMPLETED
    OverlayWindow(onDismiss = onClose, fullScreen = false) {
        Panel {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${timer.label} timer", style = CookType.headline, color = c.text, modifier = Modifier.semantics { heading() })
                Text(
                    if (done) "Done" else "${Scaling.formatDuration(Scaling.displaySeconds(timer.remainingAt(nowMs)))} left" +
                        if (timer.status == TimerStatus.PAUSED) " · paused" else "",
                    style = CookType.title, color = if (done) c.alert else c.accent,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (done) {
                        PillButton("Dismiss", icon = CookIcons.Check, primary = true, onClick = onCancel, modifier = Modifier.focusRequester(first))
                        PillButton("+1 min", icon = CookIcons.Plus, onClick = onAddMinute, contentDescription = "Restart for one more minute")
                    } else {
                        PillButton(
                            if (timer.status == TimerStatus.RUNNING) "Pause" else "Resume",
                            icon = if (timer.status == TimerStatus.RUNNING) CookIcons.Pause else CookIcons.Play,
                            primary = true, onClick = onToggle, modifier = Modifier.focusRequester(first),
                        )
                        PillButton("+1 min", icon = CookIcons.Plus, onClick = onAddMinute, contentDescription = "Add one minute")
                        PillButton("Cancel timer", icon = CookIcons.Close, onClick = onCancel)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomTimerOverlay(defaultMinutes: Int, onStart: (Int) -> Unit, onClose: () -> Unit) {
    val c = LocalCookColors.current
    var minutes by remember { mutableIntStateOf(defaultMinutes.coerceIn(1, 180)) }
    val start = requestOnShow()
    OverlayWindow(onDismiss = onClose, fullScreen = false) {
        Panel {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Set a timer", style = CookType.headline, color = c.text, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(CookIcons.Minus, "One minute less", enabled = minutes > 1) { minutes = (minutes - 1).coerceAtLeast(1) }
                    Text(
                        "$minutes min", style = CookType.display, color = c.text, textAlign = TextAlign.Center,
                        modifier = Modifier.width(200.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    RoundIconButton(CookIcons.Plus, "One minute more", enabled = minutes < 180) { minutes = (minutes + 1).coerceAtMost(180) }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(
                        "Start", icon = CookIcons.Play, primary = true, onClick = { onStart(minutes) },
                        modifier = Modifier.focusRequester(start), contentDescription = "Start a $minutes minute timer",
                    )
                    PillButton("Cancel", onClick = onClose)
                }
            }
        }
    }
}

@Composable
fun IngredientsOverlay(recipe: Recipe, servings: Int, onClose: () -> Unit) {
    val c = LocalCookColors.current
    val factor = Scaling.factor(recipe.servings, servings)
    val close = requestOnShow()
    OverlayWindow(onDismiss = onClose, fullScreen = true) {
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.fillMaxHeight().width(560.dp).background(c.surface)
                    .padding(start = 32.dp, end = Safe.horizontal, top = Safe.vertical, bottom = Safe.vertical),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ingredients", style = CookType.headline, color = c.text, modifier = Modifier.semantics { heading() })
                        Text("For $servings ${if (servings == 1) "serving" else "servings"}", style = CookType.label, color = c.textMuted)
                    }
                    PillButton("Close", icon = CookIcons.Close, onClick = onClose, modifier = Modifier.focusRequester(close))
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recipe.ingredients, key = { it.id }) { ing ->
                        var focused by remember { mutableStateOf(false) }
                        val text = Scaling.describe(ing, factor)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (focused) c.surfaceRaised else c.surface)
                                .border(3.dp, if (focused) c.focusRing else Color.Transparent, RoundedCornerShape(12.dp))
                                .onFocusChanged { focused = it.isFocused }
                                .focusable()
                                .semantics(mergeDescendants = true) { contentDescription = text }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(text, style = CookType.body, color = if (focused) c.accent else c.text)
                            ing.substitutionNotes?.let { Text("Swap idea: $it", style = CookType.label, color = c.textMuted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShoppingQrOverlay(recipe: Recipe, servings: Int, qr: ShoppingQr?, onClose: () -> Unit) {
    val c = LocalCookColors.current
    val close = requestOnShow()
    OverlayWindow(onDismiss = onClose, fullScreen = false) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (qr == null) Shimmer(Modifier.size(300.dp).clip(RoundedCornerShape(12.dp)))
                else QrCode(qr.payload, size = 300.dp)
                Spacer(Modifier.width(36.dp))
                Column(Modifier.width(380.dp)) {
                    Text("Shopping list", style = CookType.headline, color = c.text, modifier = Modifier.semantics { heading() })
                    Text(recipe.title, style = CookType.title, color = c.accent)
                    Text("For $servings ${if (servings == 1) "serving" else "servings"}", style = CookType.label, color = c.textMuted)
                    Spacer(Modifier.height(18.dp))
                    Text("Scan to view ingredients on your phone", style = CookType.body, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (qr?.mode) {
                            ShoppingQr.Mode.LAN -> "Point your phone's camera at the code. Your phone must be on the same Wi-Fi as this TV."
                            ShoppingQr.Mode.HOSTED -> "Works on any phone, on any network. The list travels inside the link; nothing is uploaded."
                            ShoppingQr.Mode.TEXT -> "This TV isn't on a network, so the code holds the list as text. Your camera app will show it as text."
                            null -> "Preparing the code..."
                        },
                        style = CookType.label.copy(fontSize = 18.sp), color = c.textMuted,
                    )
                    if (qr?.mode == ShoppingQr.Mode.LAN) {
                        Spacer(Modifier.height(6.dp))
                        Text("Or open: ${qr.payload}", style = CookType.label.copy(fontSize = 16.sp), color = c.textMuted)
                    }
                    Spacer(Modifier.height(24.dp))
                    PillButton("Done", icon = CookIcons.Check, onClick = onClose, modifier = Modifier.focusRequester(close))
                }
            }
        }
    }
}
