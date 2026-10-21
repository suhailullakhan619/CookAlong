package com.cookalong.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import com.cookalong.app.ui.components.Celebration
import com.cookalong.app.ui.components.rememberReduceMotion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.cookalong.app.data.ShoppingQr
import com.cookalong.app.ui.components.LocalOnline
import com.cookalong.app.ui.components.Shimmer
import com.cookalong.app.ui.components.ToastHost
import com.cookalong.app.ui.screens.ConfirmExitOverlay
import com.cookalong.app.ui.screens.AlexaScreen
import com.cookalong.app.ui.screens.ChefCard
import com.cookalong.app.ui.screens.CookingActions
import com.cookalong.app.ui.screens.CookingScreen
import com.cookalong.app.ui.screens.CustomTimerOverlay
import com.cookalong.app.ui.screens.DetailScreen
import com.cookalong.app.ui.screens.FridgeScanScreen
import com.cookalong.app.ui.screens.HomeFocus
import com.cookalong.app.ui.screens.HomeScreen
import com.cookalong.app.ui.screens.IngredientsOverlay
import com.cookalong.app.ui.screens.PlanScreen
import com.cookalong.app.ui.screens.SearchScreen
import com.cookalong.app.ui.screens.ShoppingQrOverlay
import com.cookalong.app.ui.screens.TimerControlsOverlay
import com.cookalong.app.ui.theme.CookAlongTheme
import com.cookalong.app.ui.theme.CookType
import com.cookalong.app.ui.theme.LocalCookColors
import com.cookalong.app.ui.theme.cookBackdrop
import com.cookalong.app.ui.theme.Safe

@Composable
fun CookAlongApp(vm: CookAlongViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsState()
    val online by vm.online.collectAsState()
    val now = vm.now.collectAsState()
    val homeList = rememberLazyListState()
    val homeRowStates = remember { mutableMapOf<Int, LazyListState>() }
    var homeFocus by remember { mutableStateOf<HomeFocus?>(null) }
    var scanFocus by remember { mutableStateOf<String?>(null) }
    var planFocus by remember { mutableStateOf<String?>(null) }

    CookAlongTheme {
      CompositionLocalProvider(LocalOnline provides online) {
        val c = LocalCookColors.current
        BackHandler { if (!vm.onBack()) onExit() }

        Box(Modifier.fillMaxSize().cookBackdrop()) {
            AnimatedContent(
                targetState = state.screen,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Loading -> Shimmer(Modifier.fillMaxSize())
                    is Screen.Failed -> Message("Something went wrong", screen.message)
                    Screen.Home -> HomeScreen(
                        state = state,
                        online = online,
                        listState = homeList,
                        rowStates = homeRowStates,
                        restoreFocus = homeFocus,
                        onOpen = { recipe, focus -> homeFocus = focus; vm.openRecipe(recipe) },
                        onFridgeScan = if (state.config.scanApiUrl.isNotBlank()) ({ vm.startFridgeScan() }) else null,
                        onAlexa = if (state.config.alexaApiUrl.isNotBlank()) ({ vm.openAlexa() }) else null,
                    )
                    is Screen.Search -> SearchScreen(
                        title = screen.title,
                        recipes = screen.recipeIds.mapNotNull(state::recipe),
                        onOpen = { vm.openRecipe(it, returnTo = screen) },
                        onBack = { vm.onBack() },
                    )
                    Screen.Alexa -> AlexaScreen(
                        alexa = state.alexa,
                        offline = !online,
                        onRetry = { vm.openAlexa(force = true) },
                        onDone = { vm.onBack() },
                        onDisconnect = vm::disconnectAlexa,
                    )
                    is Screen.Detail -> {
                        val recipe = state.recipe(screen.recipeId)
                        if (recipe == null) Message("Recipe not found", "Press Back to return to the recipe list.")
                        else DetailScreen(
                            recipe = recipe,
                            servings = state.servings,
                            minServings = state.config.minServings,
                            maxServings = state.config.maxServings,
                            onServings = vm::changeServings,
                            onStart = vm::startCooking,
                            onShowQr = { vm.showOverlay(Overlay.ShoppingQr) },
                        )
                    }
                    Screen.FridgeScan -> FridgeScanScreen(
                        scan = state.scan,
                        onOpenRecipe = { scanFocus = it.id; vm.openRecipe(it, returnTo = Screen.FridgeScan) },
                        restoreFocus = scanFocus,
                        onRetry = { vm.startFridgeScan(force = true) },
                        onBack = { vm.onBack() },
                        onPlan = if (state.config.plannerApiUrl.isNotBlank()) ({ vm.planDinner() }) else null,
                    )
                    Screen.Plan -> PlanScreen(
                        plan = state.plan,
                        findRecipe = state::recipe,
                        onOpenRecipe = { planFocus = it.id; vm.openRecipe(it, returnTo = Screen.Plan) },
                        onRetry = vm::retryPlan,
                        onBack = { vm.onBack() },
                        onShoppingQr = { vm.showOverlay(Overlay.ShoppingQr) },
                        restoreFocus = planFocus,
                    )
                    is Screen.Cooking -> {
                        val recipe = state.recipe(screen.recipeId)
                        if (recipe == null) Message("Recipe not found", "Press Back to return to the recipe list.")
                        else CookingScreen(
                            recipe = recipe,
                            stepIndex = state.stepIndex,
                            servings = state.servings,
                            timers = state.timers,
                            now = now,
                            heard = state.heard,
                            readAloud = state.readAloud,
                            actions = remember(vm) {
                                CookingActions(
                                    next = vm::nextStep,
                                    previous = vm::previousStep,
                                    primary = vm::primaryStepAction,
                                    startStepTimer = vm::startStepTimer,
                                    customTimer = { vm.showOverlay(Overlay.CustomTimer) },
                                    ingredients = { vm.showOverlay(Overlay.Ingredients) },
                                    shoppingQr = { vm.showOverlay(Overlay.ShoppingQr) },
                                    finish = vm::finishRecipe,
                                    openTimer = { vm.showOverlay(Overlay.TimerControls(it)) },
                                    toggleReadAloud = vm::toggleReadAloud,
                                )
                            },
                        )
                    }
                }
            }

            ChefCard(state.chef, Modifier.align(Alignment.BottomCenter).padding(bottom = Safe.vertical))

            ToastHost(
                state.toasts,
                Modifier.align(Alignment.BottomEnd).padding(end = Safe.horizontal, bottom = Safe.vertical + 64.dp),
            )
        }

        Overlays(state, now.value, vm)

        // "Enjoy your meal!" celebration after Finish (Back or any key skips it)
        AnimatedVisibility(state.celebrating != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(400))) {
            val title = state.celebrating ?: ""
            Box(
                Modifier.fillMaxSize().background(c.scrim)
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) { vm.endCelebration(); true } else false }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Celebration(
                    colors = listOf(c.accent, c.alert, c.focusRing, Color(0xFF6BC77B), Color(0xFF6FA8DC)),
                    checkColor = c.accent, reduceMotion = rememberReduceMotion(), modifier = Modifier.fillMaxSize(),
                )
                Column(Modifier.padding(top = 220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Enjoy your meal!", style = CookType.display.copy(fontSize = 48.sp), color = c.text)
                    Spacer(Modifier.height(8.dp))
                    Text("You cooked $title", style = CookType.title, color = c.textMuted)
                }
            }
        }
      }
    }
}

@Composable
private fun Overlays(state: UiState, nowMs: Long, vm: CookAlongViewModel) {
    val recipe = when (val s = state.screen) {
        is Screen.Detail -> state.recipe(s.recipeId)
        is Screen.Cooking -> state.recipe(s.recipeId)
        // the plan's shopping list shares the QR overlay, for the first meal
        Screen.Plan -> (state.plan as? PlanUi.Done)?.result?.plan?.meals?.firstNotNullOfOrNull { state.recipe(it.recipeId) }
        else -> null
    } ?: return
    val close = { vm.showOverlay(null) }
    when (val o = state.overlay) {
        null -> Unit
        Overlay.ConfirmExit -> ConfirmExitOverlay(recipe, state.stepIndex, state.timers, onStay = close, onLeave = vm::confirmLeaveCooking)
        Overlay.ShoppingQr -> {
            val qr by produceState<ShoppingQr?>(null, recipe.id, state.servings) { value = vm.shoppingQr(recipe, state.servings) }
            ShoppingQrOverlay(recipe, state.servings, qr, onClose = close)
        }
        Overlay.Ingredients -> IngredientsOverlay(recipe, state.servings, onClose = close)
        Overlay.CustomTimer -> CustomTimerOverlay(state.config.defaultCustomTimerMinutes, onStart = vm::startCustomTimer, onClose = close)
        is Overlay.TimerControls -> {
            val timer = state.timers.firstOrNull { it.id == o.timerId }
            if (timer == null) LaunchedEffect(o) { close() }
            else TimerControlsOverlay(
                timer = timer, nowMs = nowMs,
                onToggle = { vm.toggleTimer(timer.id); close() },
                onAddMinute = { vm.addMinute(timer.id); close() },
                onCancel = { vm.cancelTimer(timer.id) },
                onClose = close,
            )
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    val c = LocalCookColors.current
    Box(Modifier.fillMaxSize().padding(horizontal = Safe.horizontal, vertical = Safe.vertical), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = CookType.headline, color = c.text)
            Spacer(Modifier.height(12.dp))
            Text(body, style = CookType.body, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}
