package com.cookalong.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.Scaling
import com.cookalong.app.data.Step
import com.cookalong.app.data.TimerStatus
import com.cookalong.app.ui.HeardCommand
import com.cookalong.app.ui.components.CookIcons
import com.cookalong.app.ui.components.CountdownRing
import com.cookalong.app.ui.components.FocusSurface
import com.cookalong.app.ui.components.PillButton
import com.cookalong.app.ui.components.StepAnimation
import com.cookalong.app.ui.components.popOnChange
import com.cookalong.app.ui.components.rememberReduceMotion
import com.cookalong.app.ui.components.shimmerSweep
import com.cookalong.app.ui.components.sweepBorder
import com.cookalong.app.ui.components.wiggle
import com.cookalong.app.data.StepAction
import com.cookalong.app.ui.components.TagChip
import com.cookalong.app.ui.components.durationLabel
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.Safe
import com.cookalong.app.ui.components.InitialFocus

class CookingActions(
    val next: () -> Boolean,
    val previous: () -> Boolean,
    val primary: () -> Unit,
    val startStepTimer: () -> Unit,
    val customTimer: () -> Unit,
    val ingredients: () -> Unit,
    val shoppingQr: () -> Unit,
    val finish: () -> Unit,
    val openTimer: (String) -> Unit,
    val toggleReadAloud: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookingScreen(
    recipe: Recipe,
    stepIndex: Int,
    servings: Int,
    timers: List<KitchenTimer>,
    now: State<Long>,
    heard: HeardCommand?,
    readAloud: Boolean,
    actions: CookingActions,
) {
    val c = LocalCookColors.current
    val steps = recipe.orderedSteps
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val isLast = stepIndex == steps.lastIndex
    val factor = Scaling.factor(recipe.servings, servings)
    val stepFocus = remember { FocusRequester() }
    val timerFocus = remember { FocusRequester() }
    val stepTimer = timers.firstOrNull { it.stepId == step.id }
    val reduceMotion = rememberReduceMotion()

    Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = Safe.horizontal, vertical = Safe.vertical)) {
        // ---- Top: title, step count, voice caption, segmented progress
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(recipe.title, style = CookType.label.copy(fontSize = 20.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (heard != null) HeardCaption(heard)
            Spacer(Modifier.width(20.dp))
            Text(
                "Step ${stepIndex + 1} of ${steps.size}", style = CookType.title, color = c.text,
                modifier = Modifier.popOnChange(stepIndex, !reduceMotion),
            )
        }
        Spacer(Modifier.height(10.dp))
        SegmentedProgress(steps.size, stepIndex, animate = !reduceMotion)
        Spacer(Modifier.height(14.dp))

        // ---- Middle: the single step (focus home) + corner timer stack
        Row(Modifier.weight(1f).fillMaxWidth()) {
            StepPanel(
                step = step, stepIndex = stepIndex, stepCount = steps.size, recipe = recipe, factor = factor,
                hint = stepHint(step, stepTimer, hasTimers = timers.isNotEmpty()),
                actions = actions,
                reduceMotion = reduceMotion,
                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(stepFocus)
                    // Left/Right on the step change steps, so the corner timers are reached with Up.
                    .focusProperties { if (timers.isNotEmpty()) up = timerFocus },
            )
            AnimatedVisibility(timers.isNotEmpty()) {
                Column(
                    Modifier.padding(start = 20.dp).width(250.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    timers.forEachIndexed { i, t ->
                        key(t.id) {
                            // new timers slide in from the right
                            val shown = remember { MutableTransitionState(reduceMotion).apply { targetState = true } }
                            AnimatedVisibility(
                                shown,
                                enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(320)) + expandVertically(tween(320)),
                            ) {
                                TimerCard(
                                    t, now, onClick = { actions.openTimer(t.id) }, reduceMotion = reduceMotion,
                                    modifier = (if (i == 0) Modifier.focusRequester(timerFocus) else Modifier)
                                        .focusProperties { left = stepFocus },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Bottom: quiet action bar
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (step.suggestedTimerSeconds != null && (stepTimer == null || stepTimer.status == TimerStatus.COMPLETED)) {
                PillButton(
                    "${step.timerLabel ?: "Step"} ${durationLabel(step.suggestedTimerSeconds)}",
                    icon = CookIcons.Timer, compact = true, onClick = actions.startStepTimer,
                    contentDescription = "Start ${step.timerLabel ?: "step"} timer for ${Scaling.spokenDuration(step.suggestedTimerSeconds)}",
                )
            }
            PillButton("Timer", icon = CookIcons.Plus, compact = true, onClick = actions.customTimer, contentDescription = "Set a custom timer")
            PillButton("Ingredients", icon = CookIcons.List, compact = true, onClick = actions.ingredients, contentDescription = "Show all ingredients")
            // Nobody shops mid-recipe at the end; dropping it keeps Finish plus a step timer on a single row.
            if (!isLast) PillButton("Phone list", icon = CookIcons.Qr, compact = true, onClick = actions.shoppingQr, contentDescription = "Show shopping list QR code")
            PillButton(
                if (readAloud) "Read aloud: On" else "Read aloud: Off",
                icon = if (readAloud) CookIcons.SpeakerOn else CookIcons.SpeakerOff,
                compact = true, onClick = actions.toggleReadAloud,
                contentDescription = if (readAloud) "Read aloud is on. Select to turn it off" else "Read aloud is off. Select to turn it on",
            )
            if (isLast) PillButton("Finish", icon = CookIcons.Check, primary = true, compact = true, onClick = actions.finish, contentDescription = "Finish recipe")
        }
    }

    InitialFocus(stepFocus, recipe.id)
}

private fun stepHint(step: Step, timer: KitchenTimer?, hasTimers: Boolean): String {
    val label = step.timerLabel ?: "step"
    val select = when {
        timer?.status == TimerStatus.RUNNING -> "Select pauses the $label timer"
        timer?.status == TimerStatus.PAUSED -> "Select resumes the $label timer"
        timer == null && step.suggestedTimerSeconds != null -> "Select starts a ${durationLabel(step.suggestedTimerSeconds)} $label timer"
        else -> null
    }
    return listOfNotNull(select, if (hasTimers) "Up for timers" else null).joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepPanel(
    step: Step,
    stepIndex: Int,
    stepCount: Int,
    recipe: Recipe,
    factor: Double,
    hint: String,
    actions: CookingActions,
    reduceMotion: Boolean,
    modifier: Modifier,
) {
    val c = LocalCookColors.current
    var panelFocused by remember { mutableStateOf(false) }
    val related = step.relatedIngredientIds.mapNotNull { recipe.ingredient(it) }
    val spoken = buildString {
        append("Step ${stepIndex + 1} of $stepCount. ${step.instructionText} ")
        if (related.isNotEmpty()) append("You'll need: ${related.joinToString(", ") { Scaling.describe(it, factor) }}. ")
        if (hint.isNotEmpty()) append("$hint. ")
        append("Press right for the next step, left for the previous one.")
    }
    FocusSurface(
        onClick = actions.primary,
        modifier = modifier
            .onFocusChanged { panelFocused = it.hasFocus }
            .sweepBorder(panelFocused && !reduceMotion, listOf(c.focusRing, c.accent, c.alert, c.accent, c.focusRing), corner = 28.dp)
            .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionRight -> { actions.next(); true }
                Key.DirectionLeft -> { actions.previous(); true }
                else -> false
            }
        },
        shape = RoundedCornerShape(28.dp),
        color = c.surface,
        focusedColor = c.surface,
        focusedScale = 1.0f, // the panel is the screen; a ring is enough, scaling it would push content off the safe zone
        contentDescription = spoken,
    ) { focused ->
        val arrowAlpha = if (focused) 0.9f else 0.25f
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            EdgeArrow(CookIcons.ChevronLeft, visible = stepIndex > 0, alpha = arrowAlpha, modifier = Modifier.align(Alignment.CenterStart))
            EdgeArrow(CookIcons.ChevronRight, visible = stepIndex < stepCount - 1, alpha = arrowAlpha, modifier = Modifier.align(Alignment.CenterEnd))

            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { dir * it / 12 }) togetherWith
                        (fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -dir * it / 12 })
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 12.dp),
                label = "step",
            ) { index ->
                // Use this transition's own step, so the outgoing step keeps its text while it fades out.
                val shown = recipe.orderedSteps.getOrElse(index) { step }
                val shownRelated = shown.relatedIngredientIds.mapNotNull { recipe.ingredient(it) }
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StepAnimation(StepAction.of(shown), Modifier.size(width = 168.dp, height = 112.dp))
                    // The step text takes whatever room is left and shrinks (44 → 26 sp) only when it must, so the
                    // animation, ingredients and hint are never pushed off the panel.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText(
                            shown.instructionText,
                            style = CookType.step.copy(color = c.text, textAlign = TextAlign.Center, lineHeight = 1.25.em),
                            autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = CookType.step.fontSize, stepSize = 2.sp),
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (shownRelated.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            shownRelated.forEach { TagChip(Scaling.describe(it, factor)) }
                        }
                    }
                    if (hint.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(hint, style = CookType.label.copy(fontSize = 20.sp), color = c.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun EdgeArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, visible: Boolean, alpha: Float, modifier: Modifier) {
    if (!visible) return
    Icon(icon, contentDescription = null, tint = LocalCookColors.current.text.copy(alpha = alpha), modifier = modifier.size(52.dp))
}

@Composable
private fun SegmentedProgress(count: Int, current: Int, animate: Boolean) {
    val c = LocalCookColors.current
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(
                when {
                    i < current -> c.accent
                    i == current -> c.text
                    else -> c.chip
                },
                tween(250), label = "seg",
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color)
                    .shimmerSweep(animate && i == current, c.accent.copy(alpha = 0.9f)),
            )
        }
    }
}

/** Shown only after a command has actually arrived through the media session (never pre-advertised). */
@Composable
private fun HeardCaption(heard: HeardCommand) {
    val c = LocalCookColors.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.surfaceRaised).padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CookIcons.Mic, contentDescription = "Voice or media command", tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("${heard.source}: ${heard.text}", style = CookType.label, color = c.text, maxLines = 1)
    }
}

@Composable
private fun TimerCard(timer: KitchenTimer, now: State<Long>, onClick: () -> Unit, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val c = LocalCookColors.current
    val remainingMs = timer.remainingAt(now.value)
    val secs = Scaling.displaySeconds(remainingMs)
    val done = timer.status == TimerStatus.COMPLETED
    // Pulse by shifting the fill toward higher contrast (never by fading it), so text stays ≥ 7:1 throughout.
    val pulse = if (done) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a").value
    } else 0f
    val doneFill = lerp(c.alert, if (c.isDark) Color.White else Color.Black, 0.3f * pulse)
    val statusText = when (timer.status) {
        TimerStatus.RUNNING -> "running"
        TimerStatus.PAUSED -> "paused"
        TimerStatus.COMPLETED -> "done"
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
            .wiggle(!reduceMotion && timer.status == TimerStatus.RUNNING && remainingMs in 1..10_000),
        shape = RoundedCornerShape(18.dp),
        color = if (done) doneFill else c.surfaceRaised,
        focusedColor = if (done) doneFill else c.surfaceRaised,
        focusedScale = 1.05f,
        contentDescription = "${timer.label} timer, ${Scaling.spokenDuration(secs.toInt())} left, $statusText. Select for options.",
    ) {
        val fg = if (done) c.onAlert else c.text
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                CountdownRing(
                    fraction = if (timer.durationSeconds > 0) remainingMs / (timer.durationSeconds * 1000f) else 0f,
                    color = if (done) c.onAlert else c.accent,
                    track = fg.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    when (timer.status) {
                        TimerStatus.RUNNING -> CookIcons.Pause
                        TimerStatus.PAUSED -> CookIcons.Play
                        TimerStatus.COMPLETED -> CookIcons.Check
                    },
                    contentDescription = null, tint = fg, modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                // Status shares the label line: a third text line made paused cards taller and pushed the 4th timer off the stack.
                Text(
                    if (timer.status == TimerStatus.PAUSED) "Paused · ${timer.label}" else timer.label,
                    style = CookType.label, color = if (done) fg else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (done) "Done!" else Scaling.formatDuration(secs),
                    style = CookType.title.copy(fontSize = 30.sp),
                    color = if (timer.status == TimerStatus.PAUSED) c.textMuted else fg,
                )
            }
        }
    }
}

