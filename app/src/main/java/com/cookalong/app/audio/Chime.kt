package com.cookalong.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A synthesized bell chime for finished timers: no audio asset, no network, no permission. It plays on the
 * media stream because that is what Fire TV routes to the TV / soundbar.
 */
class Chime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sampleRate = 44_100
    private val pcm: ShortArray by lazy { synthesize() }

    /** Plays the chime [repeats] times. Fire-and-forget; failures (e.g. no audio output) are ignored. */
    fun play(repeats: Int = 2) {
        scope.launch {
            repeat(repeats) {
                runCatching { playOnce() }
                delay(350)
            }
        }
    }

    private fun playOnce() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / sampleRate + 50)
        } finally {
            track.release()
        }
    }

    /** Three rising bell tones (E5, G#5, B5) with soft attack and exponential decay. */
    private fun synthesize(): ShortArray {
        val notes = doubleArrayOf(659.25, 830.61, 987.77)
        val noteLen = (sampleRate * 0.42).toInt()
        val gap = (sampleRate * 0.16).toInt()
        val out = ShortArray(notes.size * gap + noteLen)
        notes.forEachIndexed { n, freq ->
            val start = n * gap
            for (i in 0 until noteLen) {
                val t = i.toDouble() / sampleRate
                val attack = (i / (sampleRate * 0.008)).coerceAtMost(1.0)
                val env = attack * exp(-t * 6.0)
                val v = sin(2 * PI * freq * t) * 0.55 + sin(2 * PI * freq * 2.01 * t) * 0.2
                val idx = start + i
                val mixed = out[idx] + (v * env * 0.45 * Short.MAX_VALUE)
                out[idx] = mixed.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            }
        }
        return out
    }
}
