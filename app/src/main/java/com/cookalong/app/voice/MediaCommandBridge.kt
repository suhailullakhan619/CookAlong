package com.cookalong.app.voice

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** Transport commands Alexa (and other media controllers) can deliver to the app on Fire TV. */
enum class MediaCommand { NEXT, PREVIOUS, PLAY, PAUSE, STOP, FAST_FORWARD, REWIND, RESTART }

/**
 * The only Fire TV voice path that works for a third-party app without Amazon-side setup: an Android
 * [MediaSessionCompat]. On Fire TV, Alexa routes "Alexa, next / previous / pause / play / stop / rewind /
 * fast-forward / restart" to the active media session (see README > Voice). Custom phrases such as "set a timer
 * for 5 minutes" need a published Alexa skill and are deliberately NOT implemented here.
 *
 * The session is only active while Cooking mode is on screen, so commands never reach the app elsewhere.
 */
class MediaCommandBridge(context: Context, private val onCommand: (MediaCommand) -> Unit) {

    private val session = MediaSessionCompat(context, "CookAlong").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onSkipToNext() = onCommand(MediaCommand.NEXT)
            override fun onSkipToPrevious() = onCommand(MediaCommand.PREVIOUS)
            override fun onPlay() = onCommand(MediaCommand.PLAY)
            override fun onPause() = onCommand(MediaCommand.PAUSE)
            override fun onStop() = onCommand(MediaCommand.STOP)
            override fun onFastForward() = onCommand(MediaCommand.FAST_FORWARD)
            override fun onRewind() = onCommand(MediaCommand.REWIND)
            override fun onSeekTo(pos: Long) {
                if (pos <= 0L) onCommand(MediaCommand.RESTART)
            }
        })
    }

    /** Publish/refresh what's "playing": the recipe and current step. */
    fun activate(recipeTitle: String, stepIndex: Int, stepCount: Int, stepText: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, recipeTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Step ${stepIndex + 1} of $stepCount")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, stepText)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, stepCount.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (stepIndex + 1).toLong())
                .build(),
        )
        // Cooking mode counts as "playing" so Alexa treats us as the foreground media app.
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackStateCompat.STATE_PLAYING, stepIndex.toLong(), 1f)
                .build(),
        )
        if (!session.isActive) session.isActive = true
    }

    fun deactivate() {
        if (session.isActive) {
            session.setPlaybackState(
                PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f).build(),
            )
            session.isActive = false
        }
    }

    fun release() {
        deactivate()
        session.release()
    }

    private companion object {
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_SEEK_TO
    }
}
