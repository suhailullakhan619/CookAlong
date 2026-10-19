package com.cookalong.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.cookalong.app.ui.CookAlongApp
import com.cookalong.app.ui.CookAlongViewModel
import com.cookalong.app.ui.Overlay
import com.cookalong.app.ui.Screen
import com.cookalong.app.voice.MediaCommandBridge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: CookAlongViewModel by viewModels()
    private lateinit var mediaBridge: MediaCommandBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaBridge = MediaCommandBridge(this, vm::onMediaCommand)
        intent?.getStringExtra(EXTRA_COMPANION_HOST)?.takeIf { it.isNotBlank() }?.let { vm.companionHostOverride = it }

        // The media session (Alexa transport commands) is live only while Cooking mode is on screen.
        lifecycleScope.launch {
            vm.state
                .map { s ->
                    val recipe = s.cookingRecipe ?: return@map null
                    val step = recipe.orderedSteps.getOrNull(s.stepIndex)
                    MediaInfo(recipe.title, s.stepIndex, recipe.steps.size, step?.instructionText.orEmpty())
                }
                .distinctUntilChanged()
                .collect { info ->
                    if (info == null) mediaBridge.deactivate()
                    else mediaBridge.activate(info.title, info.stepIndex, info.stepCount, info.stepText)
                }
        }

        setContent { CookAlongApp(vm, onExit = ::finish) }
    }

    /**
     * Physical media keys on the Fire TV remote (play/pause, rewind, fast-forward) and the Menu key. Handled here
     * so they work even when focus is on an action button, and so they never fall through to other apps.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = vm.state.value
        if (s.screen is Screen.Cooking && s.overlay == null) {
            val action: (() -> Unit)? = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> vm::togglePauseAll
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> { { vm.nextStep() } }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { { vm.previousStep() } }
                KeyEvent.KEYCODE_MENU -> { { vm.showOverlay(Overlay.Ingredients) } }
                else -> null
            }
            if (action != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground(true)
    }

    override fun onStop() {
        super.onStop()
        vm.persist()
        vm.onForeground(false)
    }

    override fun onDestroy() {
        mediaBridge.release()
        super.onDestroy()
    }

    companion object {
        /** `adb shell am start -n com.cookalong.app/.MainActivity --es companionHost <pc-ip>:8765` (emulator testing). */
        const val EXTRA_COMPANION_HOST = "companionHost"
    }

    private data class MediaInfo(val title: String, val stepIndex: Int, val stepCount: Int, val stepText: String)
}
