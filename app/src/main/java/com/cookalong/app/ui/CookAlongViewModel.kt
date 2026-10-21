package com.cookalong.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cookalong.app.ai.DetectedItem
import com.cookalong.app.alexa.AlexaClient
import com.cookalong.app.alexa.AlexaCommand
import com.cookalong.app.alexa.AlexaState
import com.cookalong.app.ai.FridgeScanClient
import com.cookalong.app.ai.PlanResult
import com.cookalong.app.ai.PlannerClient
import com.cookalong.app.audio.Chime
import com.cookalong.app.audio.Speaker
import com.cookalong.app.companion.CompanionServer
import com.cookalong.app.data.AppConfig
import com.cookalong.app.data.CatalogResult
import com.cookalong.app.data.CookingSession
import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RecipeMatch
import com.cookalong.app.data.RecipeMatcher
import com.cookalong.app.data.RecipeRepository
import com.cookalong.app.data.RecipeSearch
import com.cookalong.app.data.RemoteCatalog
import com.cookalong.app.data.Scaling
import com.cookalong.app.data.SessionStore
import com.cookalong.app.data.Speech
import com.cookalong.app.data.ShoppingList
import com.cookalong.app.data.ShoppingQr
import com.cookalong.app.data.TimerStatus
import com.cookalong.app.net.Connectivity
import com.cookalong.app.net.NoInternetException
import com.cookalong.app.timer.TimerLogic
import com.cookalong.app.voice.MediaCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.util.UUID

sealed interface Screen {
    data object Loading : Screen
    data class Failed(val message: String) : Screen
    data object Home : Screen
    data class Detail(val recipeId: String) : Screen
    data class Cooking(val recipeId: String) : Screen
    /** AI fridge scan: pairing QR -> phone photo -> Amazon Nova lists ingredients -> matching recipes. */
    data object FridgeScan : Screen
    /** Voice search results ("Alexa, tell Cook Along to find paneer"). */
    data class Search(val title: String, val recipeIds: List<String>) : Screen
    /** Connect Alexa: the pairing code, then the "connected" confirmation. */
    data object Alexa : Screen
    /** The dinner-planner agent's plan for the ingredients of the last fridge scan. */
    data object Plan : Screen
}

sealed interface PlanUi {
    data object Loading : PlanUi
    data class Done(val result: PlanResult, val servings: Int) : PlanUi
    data class Failed(val message: String) : PlanUi
    /** The TV is offline: the planner needs the internet. */
    data object NeedsInternet : PlanUi
}

sealed interface AlexaUi {
    data object Starting : AlexaUi
    /** The 6-digit code the user says to Alexa. */
    data class Code(val code: String) : AlexaUi
    data object Linked : AlexaUi
    data class Failed(val message: String) : AlexaUi
    /** The TV is offline: pairing needs the internet. */
    data object NeedsInternet : AlexaUi
}

/** An answer from "ask the chef" (via Alexa), shown on the TV while Alexa says it. */
data class ChefNote(val id: Long, val question: String, val answer: String)

sealed interface ScanUi {
    data object Starting : ScanUi
    /** Waiting for the phone; [link] is what the QR code opens. */
    data class WaitingForPhone(val code: String, val link: String, val analyzing: Boolean = false, val problem: String? = null,
        /** The TV lost its connection while waiting; polling resumes by itself when it is back. */
        val offline: Boolean = false,
    ) : ScanUi
    data class Results(val items: List<DetectedItem>, val note: String, val matches: List<RecipeMatch>) : ScanUi
    data class Failed(val message: String) : ScanUi
    /** The TV is offline: Fridge scan needs the internet. */
    data object NeedsInternet : ScanUi
}

sealed interface Overlay {
    /** Full-screen "you'll lose your progress" confirmation. */
    data object ConfirmExit : Overlay
    data object ShoppingQr : Overlay
    data object Ingredients : Overlay
    data object CustomTimer : Overlay
    data class TimerControls(val timerId: String) : Overlay
}

data class Toast(val id: Long, val text: String, val alert: Boolean = false)

/**
 * Last voice/remote command. [source] is "Heard" for the MediaSession (Alexa transport commands or any media
 * controller, we can't tell which) and "Alexa" for commands from the Cook Along skill.
 */
data class HeardCommand(val text: String, val atMs: Long, val source: String = "Heard")

data class UiState(
    val screen: Screen = Screen.Loading,
    val recipes: List<Recipe> = emptyList(),
    val config: AppConfig = AppConfig(),
    val servings: Int = 1,
    val stepIndex: Int = 0,
    val timers: List<KitchenTimer> = emptyList(),
    val overlay: Overlay? = null,
    val toasts: List<Toast> = emptyList(),
    val heard: HeardCommand? = null,
    val skippedRecipes: Int = 0,
    /** Recipes on the TV that came from the online library (cached or just synced), not bundled. */
    val onlineRecipes: Int = 0,
    /** Speak each step aloud in cooking mode. */
    val readAloud: Boolean = true,
    /** Title of the recipe just finished, while the celebration plays. */
    val celebrating: String? = null,
    val scan: ScanUi = ScanUi.Starting,
    /** Where Back goes from the open recipe: fridge-scan or search results, or Home when null. */
    val detailReturn: Screen? = null,
    /** This TV is linked to an Alexa account through the Cook Along skill. */
    val alexaLinked: Boolean = false,
    val alexa: AlexaUi = AlexaUi.Starting,
    val chef: ChefNote? = null,
    val plan: PlanUi = PlanUi.Loading,
    /** Where Back goes from the plan: the fridge-scan results, or Home when null. */
    val planReturn: Screen? = null,
    /** Ingredient names from the last fridge scan (what "Plan dinner" cooks with); empty before the first scan. */
    val scanIngredients: List<String> = emptyList(),
) {
    fun recipe(id: String): Recipe? = recipes.firstOrNull { it.id == id }
    val cookingRecipe: Recipe? get() = (screen as? Screen.Cooking)?.let { recipe(it.recipeId) }
}

class CookAlongViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RecipeRepository(app)
    private val store = SessionStore(app)
    private val chime = Chime()
    private val speaker = Speaker(app)
    private val clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Ticks only while a timer is running, so the rest of the UI doesn't recompose 4x a second. */
    private val _now = MutableStateFlow(clock())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** Whether the TV has a validated internet connection. Assumed true until Android says otherwise. */
    val online: StateFlow<Boolean> = Connectivity.stateIn(app, viewModelScope)

    private suspend fun awaitOnline(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { online.first { it } } != null

    private var tickerJob: Job? = null
    private var toastSeq = 0L

    private val companion = CompanionServer(
        pageTemplate = { app.assets.open("companion/index.html").bufferedReader().use { it.readText() } },
        findRecipe = { id -> _state.value.recipe(id) },
        readAsset = { path -> runCatching { app.assets.open(path).use { it.readBytes() } }.getOrNull() },
    )
    private var companionWanted = false

    /** Developer override for the host in the QR link, e.g. "192.168.1.20:8765" (see README). */
    var companionHostOverride: String? = null

    init {
        viewModelScope.launch { bootstrap() }
        publishToAlexaWhileLinked()
        viewModelScope.launch {
            var was = online.value
            online.collect { now ->
                if (now && !was) {
                    toast("Back online")
                    syncRecipes() // don't wait for the 10-minute timer
                    if (_state.value.alexaLinked) publishAlexaState()
                }
                was = now
            }
        }
        // Read each step aloud as it appears in cooking mode.
        viewModelScope.launch {
            _state.map { s -> (s.screen as? Screen.Cooking)?.recipeId to s.stepIndex }
                .distinctUntilChanged()
                .collect { (recipeId, _) -> if (recipeId == null) speaker.stop() else speakCurrentStep() }
        }
    }

    private fun shouldSpeak() = _state.value.readAloud && !speaker.screenReaderOn

    private fun speakCurrentStep() {
        if (!shouldSpeak()) return
        val recipe = _state.value.cookingRecipe ?: return
        val step = recipe.orderedSteps.getOrNull(_state.value.stepIndex) ?: return
        speaker.speak(Speech.forStep(_state.value.stepIndex, step.instructionText))
    }

    /** Turns read-aloud on/off; turning it on re-reads the current step straight away. */
    fun toggleReadAloud() {
        val on = !_state.value.readAloud
        if (on && !speaker.available) {
            toast("Read aloud isn't available on this TV")
            return
        }
        _state.update { it.copy(readAloud = on) }
        viewModelScope.launch { store.saveReadAloud(on) }
        if (on) speakCurrentStep() else speaker.stop()
        toast(if (on) "Read aloud on" else "Read aloud off")
    }

    private suspend fun bootstrap() {
        when (val result = repository.load()) {
            is CatalogResult.Failed -> _state.update { it.copy(screen = Screen.Failed(result.message)) }
            is CatalogResult.Loaded -> {
                val readAloud = store.loadReadAloud()
                bundled = result.recipes
                remote = result.config.recipesApiUrl.takeIf { it.isNotBlank() }?.let { RemoteCatalog(getApplication(), it) }
                // Offline-first: bundled + last downloaded recipes appear immediately...
                val cached = remote?.cached()?.recipes.orEmpty()
                _state.update {
                    it.copy(
                        recipes = RemoteCatalog.combine(bundled, cached), onlineRecipes = cached.size, config = result.config,
                        readAloud = readAloud, skippedRecipes = result.skipped, screen = Screen.Home,
                    )
                }
                restoreSession()
                // ...then anything new is fetched in the background.
                syncRecipes()
                alexa = result.config.alexaApiUrl.takeIf { it.isNotBlank() }?.let(::AlexaClient)
                if (alexa != null) {
                    deviceId = store.deviceId()
                    _state.update { it.copy(alexaLinked = store.loadAlexaLinked()) }
                    ensurePolling()
                }
            }
        }
    }

    private var bundled: List<Recipe> = emptyList()
    private var remote: RemoteCatalog? = null

    /** Downloads recipes added since the last sync. Silent when offline; a short toast when new ones arrive. */
    private var lastSyncAt = 0L
    private var syncJob: Job? = null

    fun syncRecipes() {
        val catalog = remote ?: return
        lastSyncAt = System.currentTimeMillis()
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            // Wi-Fi/DNS is often not ready right after the TV wakes, so retry a few times before giving up.
            var cache: RemoteCatalog.Cache? = null
            for (wait in listOf(0L, 5_000L, 20_000L, 60_000L)) {
                delay(wait)
                // Offline: wait for the network instead of burning a retry (a stale "offline" flag only delays by a minute).
                if (!online.value) awaitOnline(60_000)
                cache = try {
                    catalog.sync().first
                } catch (e: java.io.IOException) {
                    android.util.Log.w("CookAlong", "Recipe sync failed: $e")
                    null
                } catch (e: kotlinx.serialization.SerializationException) {
                    android.util.Log.w("CookAlong", "Recipe sync failed: $e")
                    null
                }
                if (cache != null) break
            }
            if (cache == null) return@launch // offline or backend unreachable: keep what we have
            val before = _state.value.recipes.size
            val firstDownload = _state.value.onlineRecipes == 0
            val all = RemoteCatalog.combine(bundled, cache.recipes)
            _state.update { it.copy(recipes = all, onlineRecipes = all.size - bundled.size) }
            val added = all.size - before
            if (added > 0) toast(syncToast(added, firstDownload))
        }
    }

    private suspend fun restoreSession() {
        val session = store.loadSession() ?: return
        val recipe = _state.value.recipe(session.recipeId)
        if (recipe == null) {
            store.saveSession(null) // recipe was removed from the catalog since last run
            return
        }
        val stepIndex = session.stepIndex.coerceIn(0, recipe.steps.lastIndex)
        _state.update {
            it.copy(
                screen = Screen.Cooking(recipe.id), stepIndex = stepIndex,
                servings = session.servings.coerceIn(it.config.minServings, it.config.maxServings),
                timers = session.timers,
            )
        }
        toast("Welcome back. ${recipe.title}, step ${stepIndex + 1} of ${recipe.steps.size}.")
        // Anything that finished while the app was closed alerts now.
        val (updated, finished) = TimerLogic.tick(session.timers, clock())
        _state.update { it.copy(timers = updated) }
        if (finished.isNotEmpty()) {
            chime.play()
            finished.forEach { toast("${it.label} timer finished while you were away", alert = true) }
            persist()
        }
        ensureTicker()
    }

    // ---- Navigation -------------------------------------------------------------------------------------------

    fun openRecipe(recipe: Recipe, returnTo: Screen? = null) {
        _state.update {
            it.copy(screen = Screen.Detail(recipe.id), servings = recipe.servings, overlay = null, detailReturn = returnTo)
        }
    }

    fun startCooking() {
        val detail = _state.value.screen as? Screen.Detail ?: return
        _state.update { it.copy(screen = Screen.Cooking(detail.recipeId), stepIndex = 0, timers = emptyList(), overlay = null) }
        persist()
    }

    /** @return false when the app should exit (Back on Home). */
    fun onBack(): Boolean {
        val s = _state.value
        if (s.celebrating != null) {
            endCelebration()
            return true
        }
        if (s.overlay != null) {
            _state.update { it.copy(overlay = null) }
            return true
        }
        return when (val screen = s.screen) {
            is Screen.Cooking -> {
                val hasProgress = s.stepIndex > 0 || s.timers.isNotEmpty()
                if (hasProgress) _state.update { it.copy(overlay = Overlay.ConfirmExit) } else leaveCooking(screen.recipeId)
                true
            }
            is Screen.Detail -> {
                _state.update { it.copy(screen = it.detailReturn ?: Screen.Home, detailReturn = null) }
                true
            }
            Screen.FridgeScan -> {
                scanJob?.cancel()
                _state.update { it.copy(screen = Screen.Home) }
                true
            }
            is Screen.Search -> {
                _state.update { it.copy(screen = Screen.Home) }
                true
            }
            Screen.Alexa -> {
                pairJob?.cancel()
                _state.update { it.copy(screen = Screen.Home) }
                true
            }
            Screen.Plan -> {
                planJob?.cancel()
                speaker.stop()
                _state.update { it.copy(screen = it.planReturn ?: Screen.Home, planReturn = null) }
                true
            }
            else -> false
        }
    }

    fun confirmLeaveCooking() {
        val screen = _state.value.screen as? Screen.Cooking ?: return
        leaveCooking(screen.recipeId)
    }

    private fun leaveCooking(recipeId: String) {
        _state.update { it.copy(screen = Screen.Detail(recipeId), overlay = null, timers = emptyList(), stepIndex = 0) }
        viewModelScope.launch { store.saveSession(null) }
    }

    fun finishRecipe() {
        val title = _state.value.cookingRecipe?.title ?: return
        _state.update { it.copy(screen = Screen.Home, overlay = null, timers = emptyList(), stepIndex = 0, celebrating = title) }
        viewModelScope.launch { store.saveSession(null) }
        if (shouldSpeak()) speaker.speak("Well done! Enjoy your $title.")
        viewModelScope.launch {
            delay(4_200)
            _state.update { if (it.celebrating == title) it.copy(celebrating = null) else it }
        }
    }

    fun endCelebration() = _state.update { it.copy(celebrating = null) }

    fun showOverlay(overlay: Overlay?) = _state.update { it.copy(overlay = overlay) }

    // ---- Recipe detail ----------------------------------------------------------------------------------------

    fun changeServings(delta: Int) {
        _state.update {
            it.copy(servings = (it.servings + delta).coerceIn(it.config.minServings, it.config.maxServings))
        }
    }

    // ---- Cooking ----------------------------------------------------------------------------------------------

    fun nextStep(): Boolean {
        val recipe = _state.value.cookingRecipe ?: return false
        if (_state.value.stepIndex >= recipe.steps.lastIndex) {
            toast("That's the last step. Choose Finish when you're done.")
            return false
        }
        _state.update { it.copy(stepIndex = it.stepIndex + 1) }
        persist()
        return true
    }

    fun previousStep(): Boolean {
        if (_state.value.stepIndex <= 0) return false
        _state.update { it.copy(stepIndex = it.stepIndex - 1) }
        persist()
        return true
    }

    fun goToStep(index: Int) {
        val recipe = _state.value.cookingRecipe ?: return
        _state.update { it.copy(stepIndex = index.coerceIn(0, recipe.steps.lastIndex)) }
        persist()
    }

    /**
     * Select on the step: start the step's suggested timer, or pause/resume it if it's already going.
     * Steps without a suggested timer do nothing (the action bar offers a custom timer instead).
     */
    fun primaryStepAction() {
        val recipe = _state.value.cookingRecipe ?: return
        val step = recipe.orderedSteps.getOrNull(_state.value.stepIndex) ?: return
        val existing = _state.value.timers.firstOrNull { it.stepId == step.id && it.status != TimerStatus.COMPLETED }
        when {
            existing != null -> toggleTimer(existing.id)
            step.suggestedTimerSeconds != null -> startStepTimer()
            else -> toast("No timer for this step. Press Down for more options.")
        }
    }

    fun startStepTimer() {
        val recipe = _state.value.cookingRecipe ?: return
        val step = recipe.orderedSteps.getOrNull(_state.value.stepIndex) ?: return
        val seconds = step.suggestedTimerSeconds ?: return
        startTimer(step.timerLabel ?: "Step ${_state.value.stepIndex + 1}", seconds, step.id)
    }

    fun startCustomTimer(minutes: Int) {
        startTimer("$minutes min", minutes * 60, stepId = null)
        _state.update { it.copy(overlay = null) }
    }

    private fun startTimer(label: String, seconds: Int, stepId: String?) {
        val s = _state.value
        when (val r = TimerLogic.start(s.timers, label, seconds, stepId, clock(), s.config.maxConcurrentTimers, UUID.randomUUID().toString())) {
            is TimerLogic.StartResult.Started -> {
                _state.update { it.copy(timers = r.timers) }
                toast("${r.timer.label} timer started · ${Scaling.formatDuration(seconds.toLong())}")
                persist()
                ensureTicker()
            }
            is TimerLogic.StartResult.AlreadyRunning -> toast("${r.timer.label} timer is already on")
            is TimerLogic.StartResult.LimitReached ->
                toast("You already have ${r.max} timers. Cancel one to start another.")
            TimerLogic.StartResult.InvalidDuration -> toast("Pick a time longer than zero")
        }
    }

    fun toggleTimer(id: String) {
        val t = _state.value.timers.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(timers = TimerLogic.toggle(it.timers, id, clock())) }
        toast(if (t.status == TimerStatus.RUNNING) "${t.label} paused" else "${t.label} resumed")
        persist()
        ensureTicker()
    }

    fun addMinute(id: String) {
        _state.update { it.copy(timers = TimerLogic.addSeconds(it.timers, id, 60, clock())) }
        persist()
        ensureTicker()
    }

    fun cancelTimer(id: String) {
        val t = _state.value.timers.firstOrNull { it.id == id }
        _state.update { it.copy(timers = TimerLogic.remove(it.timers, id), overlay = null) }
        if (t != null && t.status != TimerStatus.COMPLETED) toast("${t.label} timer cancelled")
        persist()
    }

    private fun pauseAll() {
        val running = _state.value.timers.count { it.status == TimerStatus.RUNNING }
        if (running == 0) { toast("No timers running"); return }
        _state.update { it.copy(timers = TimerLogic.pauseAll(it.timers, clock())) }
        toast(if (running == 1) "Timer paused" else "$running timers paused")
        persist()
    }

    private fun resumeAll() {
        val paused = _state.value.timers.count { it.status == TimerStatus.PAUSED }
        if (paused == 0) { toast("No paused timers"); return }
        _state.update { it.copy(timers = TimerLogic.resumeAll(it.timers, clock())) }
        toast(if (paused == 1) "Timer resumed" else "$paused timers resumed")
        persist()
        ensureTicker()
    }

    /** Remote play/pause key: pause everything running, otherwise resume everything paused. */
    fun togglePauseAll() {
        if (_state.value.timers.any { it.status == TimerStatus.RUNNING }) pauseAll() else resumeAll()
    }

    // ---- Media session (Alexa transport commands) --------------------------------------------------------------

    fun onMediaCommand(command: MediaCommand) {
        if (_state.value.screen !is Screen.Cooking) return
        if (_state.value.overlay == Overlay.ConfirmExit) return // never act behind the "leave?" question
        val caption = when (command) {
            MediaCommand.NEXT, MediaCommand.FAST_FORWARD -> { nextStep(); "Next step" }
            MediaCommand.PREVIOUS, MediaCommand.REWIND -> { previousStep(); "Previous step" }
            MediaCommand.RESTART -> { goToStep(0); "Back to step 1" }
            MediaCommand.PLAY -> { resumeAll(); "Resume timers" }
            MediaCommand.PAUSE, MediaCommand.STOP -> { pauseAll(); "Pause timers" }
        }
        _state.update { it.copy(heard = HeardCommand(caption, clock())) }
    }

    // ---- Alexa skill bridge -----------------------------------------------------------------------------------------
    //
    // The Cook Along skill (backend/alexa_skill.py) queues commands for this TV; we poll for them about once a
    // second while the app is on screen and linked, and publish what's on screen so Alexa can answer questions.

    private var alexa: AlexaClient? = null
    private var deviceId = ""
    private var pollJob: Job? = null
    private var pairJob: Job? = null
    private var foreground = true
    /** Last command seq seen; -1 until the first poll, so commands queued while the TV was off are never replayed. */
    private var cursor = -1L
    private var lastPublishAt = 0L

    val alexaAvailable: Boolean get() = alexa != null

    private fun wantsAlexa() = _state.value.alexaLinked || _state.value.screen == Screen.Alexa

    private fun ensurePolling() {
        val client = alexa ?: return
        if (!foreground || pollJob?.isActive == true || !wantsAlexa()) return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (foreground && wantsAlexa()) {
                // Offline: pause, and poll again the moment the network is back (no backoff to sit out). The
                // timeout is a safety net for a stale "offline" flag; a request that fails offline fails fast.
                if (!online.value) { awaitOnline(60_000); failures = 0 }
                try {
                    val reply = client.commands(deviceId, cursor)
                    failures = 0
                    val fresh = reply.commands.filter { it.seq > cursor }.sortedBy { it.seq }
                    cursor = reply.seq
                    setLinked(reply.linked)
                    // one bad command must never take the poll loop (or the app) down with it
                    fresh.forEach { c ->
                        try { onAlexaCommand(c) } catch (e: RuntimeException) {
                            android.util.Log.w("CookAlong", "Alexa command ${c.type} failed: $e")
                        }
                    }
                    // heartbeat: the skill treats a TV that hasn't published for 3 minutes as switched off
                    if (_state.value.alexaLinked && clock() - lastPublishAt > 60_000) publishAlexaState()
                } catch (e: java.io.IOException) {
                    if (online.value) failures++
                } catch (e: SerializationException) {
                    failures++
                }
                delay(if (failures == 0) 1_000L else (1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000L))
            }
        }
    }

    /** Opens Connect Alexa: gets a pairing code, or shows the "connected" screen if this TV is already linked. */
    fun openAlexa(force: Boolean = false) {
        val client = alexa ?: return
        pairJob?.cancel()
        if (_state.value.alexaLinked) {
            _state.update { it.copy(screen = Screen.Alexa, alexa = AlexaUi.Linked, overlay = null) }
            return
        }
        if (!force && !online.value) {
            _state.update { it.copy(screen = Screen.Alexa, alexa = AlexaUi.NeedsInternet, overlay = null) }
            return
        }
        _state.update { it.copy(screen = Screen.Alexa, alexa = AlexaUi.Starting, overlay = null) }
        pairJob = viewModelScope.launch {
            val reply = try {
                client.pair(deviceId)
            } catch (e: NoInternetException) {
                _state.update { it.copy(alexa = AlexaUi.NeedsInternet) }
                return@launch
            } catch (e: java.io.IOException) {
                _state.update { it.copy(alexa = AlexaUi.Failed(e.message ?: "Couldn't get a code.")) }
                return@launch
            }
            _state.update { it.copy(alexa = AlexaUi.Code(reply.code)) }
            ensurePolling()
            delay(reply.expiresIn * 1000L)
            _state.update {
                if (it.alexa is AlexaUi.Code) it.copy(alexa = AlexaUi.Failed("That code expired. Get a new one when you're ready.")) else it
            }
        }
    }

    fun disconnectAlexa() {
        val client = alexa ?: return
        viewModelScope.launch {
            try {
                client.unlink(deviceId)
                setLinked(false)
                _state.update { it.copy(screen = Screen.Home) }
            } catch (e: java.io.IOException) {
                toast(e.message ?: "Couldn't disconnect Alexa")
            }
        }
    }

    private fun setLinked(linked: Boolean) {
        if (_state.value.alexaLinked == linked) return
        _state.update { it.copy(alexaLinked = linked, alexa = if (linked) AlexaUi.Linked else AlexaUi.Starting) }
        viewModelScope.launch { store.saveAlexaLinked(linked) }
        toast(if (linked) "Alexa connected" else "Alexa disconnected")
        if (linked && shouldSpeak()) speaker.speak("Alexa is connected.")
    }

    /** Publishes what's on screen whenever it changes (debounced), only while linked. */
    @OptIn(FlowPreview::class)
    private fun publishToAlexaWhileLinked() {
        viewModelScope.launch {
            _state.map { s ->
                if (!s.alexaLinked) null
                else listOf(s.screen, s.servings, s.stepIndex, s.timers, s.readAloud, s.recipes.size, s.scanIngredients)
            }
                .distinctUntilChanged()
                .debounce(400)
                .collect { if (it != null) publishAlexaState() }
        }
    }

    private suspend fun publishAlexaState() {
        val client = alexa ?: return
        val s = _state.value
        if (!s.alexaLinked || !online.value) return // offline: republished when the connection returns
        val screenName = when (s.screen) {
            is Screen.Detail -> "detail"
            is Screen.Cooking -> "cooking"
            Screen.FridgeScan -> "scan"
            is Screen.Search -> "search"
            Screen.Alexa -> "alexa"
            Screen.Plan -> "plan"
            else -> "home"
        }
        val state = AlexaState.of(
            screenName, recipeOnScreen(), s.servings, s.config.minServings, s.config.maxServings,
            s.stepIndex, s.timers, tvSpeaks = shouldSpeak(), nowMs = clock(), scanIngredients = s.scanIngredients,
        )
        try {
            client.publish(deviceId, state)
            lastPublishAt = clock()
        } catch (e: java.io.IOException) {
            android.util.Log.w("CookAlong", "Alexa state publish failed: $e")
        }
    }

    private fun recipeOnScreen(): Recipe? = when (val sc = _state.value.screen) {
        is Screen.Detail -> _state.value.recipe(sc.recipeId)
        is Screen.Cooking -> _state.value.recipe(sc.recipeId)
        else -> null
    }

    /** Acts on a command from the Cook Along skill. The skill already checked it makes sense for this screen. */
    fun onAlexaCommand(cmd: AlexaCommand) {
        if (_state.value.overlay == Overlay.ConfirmExit) showOverlay(null) // talking to Alexa means "stay"
        if (_state.value.celebrating != null) endCelebration()
        val cooking = _state.value.screen is Screen.Cooking
        val caption: String? = when (cmd.type) {
            "linked" -> { setLinked(true); null }
            "next" -> { nextStep(); "Next step" }
            "previous" -> { previousStep(); "Previous step" }
            "repeat" -> { speakCurrentStep(); "Repeat step" }
            "goto" -> { goToStep(cmd.step ?: 0); "Step ${(cmd.step ?: 0) + 1}" }
            "finish" -> { finishRecipe(); null }
            "startStepTimer" -> { startStepTimer(); "Start the step timer" }
            "timer" -> { if (cooking) cmd.seconds?.let(::startVoiceTimer); "New timer" }
            "addTime" -> { addTime(cmd.timerId, cmd.seconds ?: 60); "Add time" }
            "pause" -> { pauseAll(); "Pause timers" }
            "resume" -> { resumeAll(); "Resume timers" }
            "cancelTimer" -> { cmd.timerId?.let(::cancelTimer); "Cancel timer" }
            "dismissFinished" -> { dismissFinishedTimers(); "Timer dismissed" }
            "ingredients" -> { if (recipeOnScreen() != null) showOverlay(Overlay.Ingredients); "Ingredients" }
            "servings" -> { if (recipeOnScreen() != null) cmd.count?.let(::setServings); "Serves ${cmd.count}" }
            "startCooking" -> { startCooking(); null }
            "search" -> { search(cmd.query.orEmpty(), cmd.maxMinutes); null }
            "shoppingList" -> { if (recipeOnScreen() != null) showOverlay(Overlay.ShoppingQr); "Shopping list" }
            "fridgeScan" -> { if (!cooking) startFridgeScan(); null }
            "plan" -> { if (!cooking) planDinner(servings = cmd.servings ?: 2); null }
            "chef" -> { showChef(cmd.question.orEmpty(), cmd.text.orEmpty()); null }
            else -> null
        }
        if (caption == null) return
        if (_state.value.screen is Screen.Cooking) _state.update { it.copy(heard = HeardCommand(caption, clock(), source = "Alexa")) }
        else toast("Alexa: $caption")
    }

    private fun startVoiceTimer(seconds: Int) {
        val label = if (seconds % 60 == 0) "${seconds / 60} min" else Scaling.formatDuration(seconds.toLong())
        startTimer(label, seconds, stepId = null)
    }

    /** Adds time to [timerId], or to the timer that ends soonest if that one is gone. */
    private fun addTime(timerId: String?, seconds: Int) {
        val now = clock()
        val active = _state.value.timers.filter { it.status != TimerStatus.COMPLETED }
        val target = active.firstOrNull { it.id == timerId } ?: active.minByOrNull { it.remainingAt(now) } ?: return
        _state.update { it.copy(timers = TimerLogic.addSeconds(it.timers, target.id, seconds, now)) }
        val amount = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds sec"
        toast("Added $amount to ${target.label}")
        persist()
        ensureTicker()
    }

    private fun dismissFinishedTimers() {
        _state.update { s -> s.copy(timers = s.timers.filterNot { it.status == TimerStatus.COMPLETED }) }
        persist()
    }

    private fun setServings(n: Int) {
        _state.update { it.copy(servings = n.coerceIn(it.config.minServings, it.config.maxServings)) }
        persist()
    }

    /** Voice search: opens the recipe straight away if the user named one exactly, otherwise lists matches. */
    fun search(query: String, maxMinutes: Int? = null) {
        val s = _state.value
        if (s.screen is Screen.Cooking) return
        RecipeSearch.exactTitle(s.recipes, query)?.let { openRecipe(it); return }
        val hits = RecipeSearch.find(s.recipes, query, maxMinutes)
        val title = if (query.isBlank() && maxMinutes != null) "Ready in $maxMinutes minutes or less" else "Recipes for “$query”"
        _state.update { it.copy(screen = Screen.Search(title, hits.map { r -> r.id }), overlay = null) }
    }

    private fun showChef(question: String, answer: String) {
        if (answer.isBlank()) return
        val note = ChefNote(++toastSeq, question, answer)
        _state.update { it.copy(chef = note) }
        viewModelScope.launch {
            delay(16_000)
            _state.update { if (it.chef?.id == note.id) it.copy(chef = null) else it }
        }
    }

    fun dismissChef() = _state.update { it.copy(chef = null) }

    // ---- AI fridge scan ------------------------------------------------------------------------------------------

    private var scanJob: Job? = null

    /** Starts a scan: get a pairing code, show its QR, then poll until the phone's photo has been analysed. */
    fun startFridgeScan(force: Boolean = false) {
        val config = _state.value.config
        if (config.scanApiUrl.isBlank()) return
        scanJob?.cancel()
        if (!force && !online.value) {
            _state.update { it.copy(screen = Screen.FridgeScan, scan = ScanUi.NeedsInternet, overlay = null) }
            return
        }
        _state.update { it.copy(screen = Screen.FridgeScan, scan = ScanUi.Starting, overlay = null) }
        val client = FridgeScanClient(config.scanApiUrl)
        scanJob = viewModelScope.launch {
            val code = try {
                client.startSession()
            } catch (e: NoInternetException) {
                _state.update { it.copy(scan = ScanUi.NeedsInternet) }
                return@launch
            } catch (e: java.io.IOException) {
                _state.update { it.copy(scan = ScanUi.Failed(e.message ?: "Couldn't start a fridge scan.")) }
                return@launch
            }
            val link = config.scanPageUrl + "#c=" + code + "&a=" +
                java.net.URLEncoder.encode(config.scanApiUrl, "UTF-8")
            _state.update { it.copy(scan = ScanUi.WaitingForPhone(code, link)) }
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            // The phone can send another photo on the same code (3 per code), so keep listening after a result or a
            // failed photo: a retry on the phone then shows up here instead of leaving the TV stuck.
            var shown: List<DetectedItem>? = null
            var shownProblem: String? = null
            var failures = 0
            fun markOffline(offline: Boolean) = _state.update { s ->
                val cur = s.scan
                if (cur is ScanUi.WaitingForPhone && cur.offline != offline) s.copy(scan = cur.copy(offline = offline)) else s
            }
            while (System.currentTimeMillis() < deadline) {
                delay(if (shown == null) 2_000 else 4_000)
                if (!online.value) {
                    // Say so right away, then wait for the network (the code lasts 15 minutes) and poll at once.
                    markOffline(true)
                    awaitOnline((deadline - System.currentTimeMillis()).coerceIn(1L, 60_000L))
                }
                val poll = try {
                    client.poll(code).also { failures = 0; markOffline(false) }
                } catch (e: java.io.IOException) {
                    if (++failures >= 2) markOffline(true) // one blip isn't "lost connection"
                    FridgeScanClient.Poll.Waiting
                }
                when (poll) {
                    FridgeScanClient.Poll.Waiting -> Unit
                    FridgeScanClient.Poll.Analyzing -> _state.update { s ->
                        when (val cur = s.scan) {
                            is ScanUi.WaitingForPhone -> if (cur.analyzing && cur.problem == null) s else s.copy(scan = cur.copy(analyzing = true, problem = null))
                            is ScanUi.Results -> s.copy(scan = ScanUi.WaitingForPhone(code, link, analyzing = true))
                            else -> s
                        }
                    }
                    is FridgeScanClient.Poll.Done -> if (poll.items != shown || _state.value.scan !is ScanUi.Results) {
                        shown = poll.items
                        shownProblem = null
                        val names = poll.items.map { it.name }
                        _state.update { it.copy(scanIngredients = names) }
                        val recipes = _state.value.recipes
                        val matches = withContext(Dispatchers.Default) { RecipeMatcher.rank(recipes, names) }
                        _state.update { it.copy(scan = ScanUi.Results(poll.items, poll.note, matches)) }
                        val ready = matches.count { it.tier == com.cookalong.app.data.MatchTier.READY }
                        if (shouldSpeak() && _state.value.screen == Screen.FridgeScan) speaker.speak(
                            when {
                                poll.items.isEmpty() -> "I couldn't spot any food in that photo."
                                matches.isEmpty() -> "I couldn't find a recipe for those ingredients yet."
                                ready > 0 -> "You can cook $ready ${if (ready == 1) "recipe" else "recipes"} right now. Best match: ${matches.first().recipe.title}."
                                else -> "Nothing is ready, but ${matches.first().recipe.title} is close."
                            },
                        )
                    }
                    is FridgeScanClient.Poll.Failed -> if (poll.message != shownProblem || _state.value.scan !is ScanUi.WaitingForPhone) {
                        shownProblem = poll.message
                        shown = null
                        _state.update { it.copy(scan = ScanUi.WaitingForPhone(code, link, problem = poll.message)) }
                    }
                    FridgeScanClient.Poll.Expired -> break
                }
            }
            // Only a TV still waiting has failed; one already showing results just stops listening.
            if (_state.value.scan !is ScanUi.Results) {
                _state.update { it.copy(scan = ScanUi.Failed("The scan timed out. Start a new one when you're ready.")) }
            }
        }
    }

    // ---- Dinner planner (AWS agent) -------------------------------------------------------------------------------------

    private var planJob: Job? = null
    private var planIngredients: List<String> = emptyList()
    private var planServings = 2

    /**
     * Asks the dinner-planner agent (Strands on Amazon Nova, behind [PlannerClient]) what to cook with [ingredients],
     * by default the last fridge scan's, and shows the Plan screen. Called by the "Plan dinner" button, by Alexa's
     * `plan` command (with the servings she heard) and by Try again.
     */
    fun planDinner(servings: Int = 2, ingredients: List<String> = _state.value.scanIngredients, force: Boolean = false) {
        val s = _state.value
        if (s.config.plannerApiUrl.isBlank() || s.screen is Screen.Cooking) return
        if (ingredients.isEmpty()) {
            toast("Scan your fridge first")
            return
        }
        val n = servings.coerceIn(s.config.minServings, s.config.maxServings)
        planIngredients = ingredients
        planServings = n
        val back = when (s.screen) {
            Screen.Plan -> s.planReturn
            Screen.FridgeScan -> Screen.FridgeScan
            else -> null
        }
        planJob?.cancel()
        speaker.stop()
        if (!force && !online.value) {
            _state.update { it.copy(screen = Screen.Plan, plan = PlanUi.NeedsInternet, planReturn = back, overlay = null) }
            return
        }
        _state.update { it.copy(screen = Screen.Plan, plan = PlanUi.Loading, planReturn = back, servings = n, overlay = null) }
        planJob = viewModelScope.launch {
            val result = try {
                PlannerClient(s.config.plannerApiUrl, store.deviceId()).plan(ingredients, n)
            } catch (e: NoInternetException) {
                _state.update { it.copy(plan = PlanUi.NeedsInternet) }
                return@launch
            } catch (e: java.io.IOException) {
                _state.update { it.copy(plan = PlanUi.Failed(e.message ?: "The planner couldn't finish.")) }
                return@launch
            }
            _state.update { it.copy(plan = PlanUi.Done(result, n)) }
            if (shouldSpeak() && _state.value.screen == Screen.Plan) {
                speaker.speak(result.plan.summary.ifBlank { "I couldn't find a dinner for those ingredients." })
            }
        }
    }

    fun retryPlan() = planDinner(planServings, planIngredients, force = true)

    // ---- Shopping-list QR ---------------------------------------------------------------------------------------

    suspend fun shoppingQr(recipe: Recipe, servings: Int): ShoppingQr = withContext(Dispatchers.IO) {
        val base = _state.value.config.companionBaseUrl
        val lan = if (base.isBlank()) {
            companionWanted = true
            companion.start(companionHostOverride)
        } else null
        ShoppingList.qr(recipe, servings, base, lan)
    }

    /** Called from Activity.onStart / onStop: the LAN page only runs while CookAlong is on screen. */
    fun onForeground(visible: Boolean) {
        foreground = visible
        // commands queued while the app was in the background must not replay when it comes back
        if (!visible) cursor = -1L
        if (visible) ensurePolling() else pollJob?.cancel()
        // pick up newly published recipes when the user comes back (at most every 10 minutes)
        if (visible && lastSyncAt > 0 && System.currentTimeMillis() - lastSyncAt > 10 * 60_000L) syncRecipes()
        viewModelScope.launch(Dispatchers.IO) {
            if (!visible) companion.stop() else if (companionWanted) companion.start(companionHostOverride)
        }
    }

    override fun onCleared() {
        companion.stop()
        speaker.shutdown()
    }

    // ---- Toasts -----------------------------------------------------------------------------------------------

    fun toast(text: String, alert: Boolean = false) {
        val t = Toast(++toastSeq, text, alert)
        // A repeated message (e.g. Right on the last step) replaces its twin instead of stacking a duplicate.
        _state.update { it.copy(toasts = (it.toasts.filterNot { o -> o.text == text } + t).takeLast(3)) }
        viewModelScope.launch {
            delay(if (alert) 8_000 else 3_500)
            _state.update { s -> s.copy(toasts = s.toasts.filterNot { it.id == t.id }) }
        }
    }

    // ---- Timers ticking & persistence ---------------------------------------------------------------------------

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (_state.value.timers.any { it.status == TimerStatus.RUNNING }) {
                val now = clock()
                _now.value = now
                val (updated, finished) = TimerLogic.tick(_state.value.timers, now)
                if (finished.isNotEmpty()) {
                    _state.update { it.copy(timers = updated) }
                    chime.play()
                    finished.forEach { toast("${it.label} timer is done", alert = true) }
                    if (shouldSpeak()) speaker.speak(finished.joinToString(". ") { "${it.label} timer is done" }, queue = true)
                    persist()
                }
                delay(250)
            }
            _now.value = clock()
        }
    }

    /** Saves recipe + step + servings + timers whenever they change. Also called from Activity.onStop. */
    fun persist() {
        val s = _state.value
        val cooking = s.screen as? Screen.Cooking ?: return
        val session = CookingSession(cooking.recipeId, s.stepIndex, s.servings, s.timers)
        viewModelScope.launch { store.saveSession(session) }
    }
}

/** Toast after a recipe sync. The first download of the online library isn't "429 new recipes". */
internal fun syncToast(added: Int, firstDownload: Boolean): String = when {
    firstDownload -> if (added == 1) "1 more recipe downloaded" else "$added more recipes downloaded"
    added == 1 -> "1 new recipe added"
    else -> "$added new recipes added"
}
