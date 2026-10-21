package com.cookalong.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Reads steps aloud. When a [cloud] voice is set and the TV is online, each sentence is spoken in Amazon Polly's natural
 * voice (fetched once, then cached); otherwise, or if that fails, the device's built-in text-to-speech engine speaks
 * (offline on Fire TV; no permission needed).
 *
 * TTS voices are often quiet next to TV audio, so speech is synthesized to a small file first and played with a
 * [LoudnessEnhancer] (which includes a limiter, so the boost doesn't clip). If that path fails on a device, it falls
 * back to plain `TextToSpeech.speak`. Some Fire TV sticks ship a default engine without any voice data installed, so
 * every installed engine is tried until one has an English voice. If none works, [available] turns false and calls do
 * nothing.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    /** Engines still to try if the current one has no usable voice; "" means the system default engine. */
    private val engines = ArrayDeque<String>().apply {
        add("")
        runCatching {
            appContext.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }.distinct().forEach { add(it) }
        }
    }
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: String? = null

    // Boosted playback state (main thread only)
    private var generation = 0
    private var seq = 0
    /** Audio waiting to play; [Clip.keep] files belong to the cloud-voice cache and are never deleted here. */
    private data class Clip(val file: File, val keep: Boolean)
    private val queue = ArrayDeque<Clip>()
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var boostWorks = true
    /** Text of each synthesis still in flight, by utterance id: lets us speak it plainly if synthesis fails. */
    private val inFlight = ConcurrentHashMap<String, String>()

    /** Natural voice (Amazon Polly). Set once the app knows its backend; null = device voice only. */
    @Volatile var cloud: CloudVoice? = null
    private val fetcher = Executors.newSingleThreadExecutor()

    /** False once we know this device can't speak. */
    val available: Boolean get() = !failed || cloud != null

    /** A screen reader (VoiceView/TalkBack) already announces steps; speaking too would talk over it. */
    val screenReaderOn: Boolean
        get() = accessibility?.let { it.isEnabled && it.isTouchExplorationEnabled } == true

    init {
        tryNextEngine()
    }

    private fun tryNextEngine() {
        tts?.shutdown()
        tts = null
        if (engines.isEmpty()) {
            Log.w(TAG, "No text-to-speech engine with a usable voice")
            failed = true
            return
        }
        val engine = engines.poll().takeIf { it.isNotEmpty() }
        Log.i(TAG, "Trying TTS engine ${engine ?: "(default)"}")
        tts = if (engine == null) TextToSpeech(appContext, this) else TextToSpeech(appContext, this, engine)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            main.post { tryNextEngine() }
            return
        }
        val voice = listOf(Locale.getDefault(), Locale.US, Locale.UK, Locale("en", "IN"), Locale.ENGLISH)
            .firstOrNull { t.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        Log.i(TAG, "TTS engine ${t.defaultEngine}, voice ${voice ?: "none"}")
        if (voice == null) {
            main.post { tryNextEngine() }
            return
        }
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                val (gen, file) = parse(utteranceId) ?: return
                inFlight.remove(utteranceId)
                main.post { if (gen == generation) { queue.add(Clip(file, keep = false)); playNextIfIdle() } else file.delete() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.w(TAG, "TTS synthesis error")
                val (gen, file) = parse(utteranceId) ?: return
                file.delete()
                val text = inFlight.remove(utteranceId) ?: return
                main.post { if (gen == generation) speakPlain(text, queue = false) }
            }
        })
        ready = true
        main.post {
            pending?.let { speak(it) }
            pending = null
        }
    }

    /** Speaks [text], interrupting anything still being read unless [queue] is true. Call on the main thread. */
    fun speak(text: String, queue: Boolean = false) {
        val voice = cloud
        if (voice == null) {
            speakLocal(text, queue)
            return
        }
        if (!queue) {
            tts?.stop()
            stopPlayback()
        }
        val gen = generation
        fetcher.execute {
            val file = voice.audioFor(text)
            main.post {
                if (gen != generation) return@post // interrupted by a newer request while fetching
                if (file != null) {
                    this.queue.add(Clip(file, keep = true))
                    playNextIfIdle()
                } else {
                    speakLocal(text, queue = true)
                }
            }
        }
    }

    /** Gets [text]'s natural-voice audio ready in the background (no-op without a cloud voice). */
    fun prefetch(text: String) {
        val voice = cloud ?: return
        fetcher.execute { voice.audioFor(text) }
    }

    /** The device's own text-to-speech engine. */
    private fun speakLocal(text: String, queue: Boolean) {
        val t = tts ?: return
        if (!ready) {
            if (!failed) pending = text
            return
        }
        if (!boostWorks) {
            speakPlain(text, queue)
            return
        }
        if (!queue) stopPlayback()
        val file = File(appContext.cacheDir, "speech-${seq++}.wav")
        val id = "$generation|${file.absolutePath}"
        inFlight[id] = text
        val result = t.synthesizeToFile(text, Bundle(), file, id)
        if (result != TextToSpeech.SUCCESS) {
            inFlight.remove(id)
            boostWorks = false
            speakPlain(text, queue)
            return
        }
        // Some engines (e.g. the OEM voice on some Fire TV sticks) accept the request but never call back.
        val gen = generation
        main.postDelayed({
            if (inFlight.remove(id) != null && gen == generation) {
                Log.w(TAG, "TTS synthesis timed out; speaking directly from now on")
                boostWorks = false
                file.delete()
                speakPlain(text, queue = false)
            }
        }, SYNTH_TIMEOUT_MS)
    }

    private fun speakPlain(text: String, queue: Boolean) {
        val r = tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "cookalong")
        Log.i(TAG, "plain speak result $r")
    }

    fun stop() {
        pending = null
        if (!ready) return
        tts?.stop()
        stopPlayback()
    }

    fun shutdown() {
        stop()
        fetcher.shutdownNow()
        tts?.shutdown()
        tts = null
    }

    private fun parse(utteranceId: String): Pair<Int, File>? {
        val gen = utteranceId.substringBefore('|').toIntOrNull() ?: return null
        return gen to File(utteranceId.substringAfter('|'))
    }

    private fun stopPlayback() {
        generation++ // anything still being synthesized for the old request is dropped when it finishes
        queue.filterNot { it.keep }.forEach { it.file.delete() }
        queue.clear()
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        player?.release()
        enhancer?.release()
        player = null
        enhancer = null
    }

    private fun playNextIfIdle() {
        if (player != null) return
        val (file, keep) = queue.poll() ?: return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            enhancer = runCatching {
                LoudnessEnhancer(mp.audioSessionId).apply { setTargetGain(BOOST_MILLIBELS); enabled = true }
            }.getOrNull()
            mp.setOnCompletionListener {
                releasePlayer()
                if (!keep) file.delete()
                playNextIfIdle()
            }
            player = mp
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Playback failed: $e")
            releasePlayer()
            file.delete() // a broken cloud file is dropped from the cache too
            // Couldn't play the boosted local file: drop to the engine's normal output from now on.
            if (!keep) boostWorks = false
            playNextIfIdle()
        }
    }

    private companion object {
        const val TAG = "CookAlongSpeech"
        const val SYNTH_TIMEOUT_MS = 4_000L
        /** +9 dB. LoudnessEnhancer limits peaks, so this raises quiet TTS voices without distortion. */
        const val BOOST_MILLIBELS = 900
    }
}
