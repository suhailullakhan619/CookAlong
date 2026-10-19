package com.cookalong.app.timer

import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.TimerStatus

/**
 * Pure, clock-injected timer operations over an immutable list. The ViewModel owns the list; this keeps the
 * edge cases (limits, duplicates, completion while away) unit-testable without Android.
 */
object TimerLogic {

    sealed interface StartResult {
        data class Started(val timers: List<KitchenTimer>, val timer: KitchenTimer) : StartResult
        /** A timer for this step is already counting; we don't start a duplicate. */
        data class AlreadyRunning(val timer: KitchenTimer) : StartResult
        data class LimitReached(val max: Int) : StartResult
        data object InvalidDuration : StartResult
    }

    fun start(
        timers: List<KitchenTimer>,
        label: String,
        seconds: Int,
        stepId: String?,
        nowMs: Long,
        maxConcurrent: Int,
        id: String,
    ): StartResult {
        if (seconds <= 0) return StartResult.InvalidDuration
        if (stepId != null) {
            timers.firstOrNull { it.stepId == stepId && it.status != TimerStatus.COMPLETED }
                ?.let { return StartResult.AlreadyRunning(it) }
        }
        // Finished timers the user hasn't dismissed don't count toward the limit; drop them to make room.
        val active = timers.filter { it.status != TimerStatus.COMPLETED }
        if (active.size >= maxConcurrent.coerceAtLeast(1)) return StartResult.LimitReached(maxConcurrent)
        val timer = KitchenTimer(
            id = id,
            label = label.ifBlank { "Timer" },
            durationSeconds = seconds,
            status = TimerStatus.RUNNING,
            remainingMs = seconds * 1000L,
            endsAtEpochMs = nowMs + seconds * 1000L,
            stepId = stepId,
        )
        val kept = if (timers.size >= maxConcurrent) active else timers
        return StartResult.Started(kept + timer, timer)
    }

    fun pause(timers: List<KitchenTimer>, id: String, nowMs: Long) = timers.map {
        if (it.id == id && it.status == TimerStatus.RUNNING)
            it.copy(status = TimerStatus.PAUSED, remainingMs = it.remainingAt(nowMs))
        else it
    }

    fun resume(timers: List<KitchenTimer>, id: String, nowMs: Long) = timers.map {
        if (it.id == id && it.status == TimerStatus.PAUSED)
            it.copy(status = TimerStatus.RUNNING, endsAtEpochMs = nowMs + it.remainingMs)
        else it
    }

    fun toggle(timers: List<KitchenTimer>, id: String, nowMs: Long): List<KitchenTimer> {
        val t = timers.firstOrNull { it.id == id } ?: return timers
        return if (t.status == TimerStatus.RUNNING) pause(timers, id, nowMs) else resume(timers, id, nowMs)
    }

    fun pauseAll(timers: List<KitchenTimer>, nowMs: Long) =
        timers.fold(timers) { acc, t -> pause(acc, t.id, nowMs) }

    fun resumeAll(timers: List<KitchenTimer>, nowMs: Long) =
        timers.fold(timers) { acc, t -> resume(acc, t.id, nowMs) }

    fun addSeconds(timers: List<KitchenTimer>, id: String, seconds: Int, nowMs: Long) = timers.map {
        if (it.id != id) return@map it
        when (it.status) {
            TimerStatus.RUNNING -> it.copy(endsAtEpochMs = it.endsAtEpochMs + seconds * 1000L)
            TimerStatus.PAUSED -> it.copy(remainingMs = it.remainingMs + seconds * 1000L)
            // "+1 min" on a finished timer restarts it for that minute.
            TimerStatus.COMPLETED -> it.copy(
                status = TimerStatus.RUNNING, remainingMs = seconds * 1000L, endsAtEpochMs = nowMs + seconds * 1000L,
            )
        }
    }

    fun remove(timers: List<KitchenTimer>, id: String) = timers.filterNot { it.id == id }

    /** Marks running timers whose end time has passed as completed. Returns the new list and the ones that just finished. */
    fun tick(timers: List<KitchenTimer>, nowMs: Long): Pair<List<KitchenTimer>, List<KitchenTimer>> {
        val finished = mutableListOf<KitchenTimer>()
        val updated = timers.map {
            if (it.status == TimerStatus.RUNNING && nowMs >= it.endsAtEpochMs) {
                it.copy(status = TimerStatus.COMPLETED, remainingMs = 0).also(finished::add)
            } else it
        }
        return updated to finished
    }
}
