package com.cookalong.app

import com.cookalong.app.data.KitchenTimer
import com.cookalong.app.data.TimerStatus
import com.cookalong.app.timer.TimerLogic
import com.cookalong.app.timer.TimerLogic.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerLogicTest {

    private fun start(timers: List<KitchenTimer>, id: String, seconds: Int = 60, step: String? = null, now: Long = 0, max: Int = 4) =
        TimerLogic.start(timers, "T$id", seconds, step, now, max, id)

    private fun started(r: StartResult) = (r as StartResult.Started).timers

    @Test fun runsToCompletionAndReportsOnce() {
        var timers = started(start(emptyList(), "a", seconds = 10, now = 1_000))
        val (t1, done1) = TimerLogic.tick(timers, 5_000)
        assertTrue(done1.isEmpty())
        assertEquals(6_000, t1[0].remainingAt(5_000))
        val (t2, done2) = TimerLogic.tick(t1, 11_000)
        assertEquals(listOf("a"), done2.map { it.id })
        assertEquals(TimerStatus.COMPLETED, t2[0].status)
        val (_, done3) = TimerLogic.tick(t2, 20_000)
        assertTrue("completion must alert only once", done3.isEmpty())
        timers = t2
        assertEquals(0, timers[0].remainingAt(30_000))
    }

    @Test fun pauseFreezesAndResumeContinues() {
        var timers = started(start(emptyList(), "a", seconds = 60, now = 0))
        timers = TimerLogic.pause(timers, "a", 20_000)
        assertEquals(40_000, timers[0].remainingAt(50_000))
        timers = TimerLogic.resume(timers, "a", 100_000)
        assertEquals(30_000, timers[0].remainingAt(110_000))
        val (_, done) = TimerLogic.tick(timers, 140_000)
        assertEquals(1, done.size)
    }

    @Test fun pausedTimersNeverComplete() {
        val timers = TimerLogic.pause(started(start(emptyList(), "a", seconds = 5)), "a", 1_000)
        val (_, done) = TimerLogic.tick(timers, 1_000_000)
        assertTrue(done.isEmpty())
    }

    @Test fun noDuplicateTimerForTheSameStep() {
        val timers = started(start(emptyList(), "a", step = "s1"))
        val again = start(timers, "b", step = "s1")
        assertTrue(again is StartResult.AlreadyRunning)
        // ...but once it has finished, the step's timer can be started again
        val (finished, _) = TimerLogic.tick(timers, 999_999)
        assertTrue(start(finished, "c", step = "s1") is StartResult.Started)
    }

    @Test fun concurrencyLimitIgnoresFinishedTimers() {
        var timers = started(start(emptyList(), "a", seconds = 1, max = 2))
        timers = started(start(timers, "b", seconds = 100, max = 2))
        assertTrue(start(timers, "c", max = 2) is StartResult.LimitReached)
        timers = TimerLogic.tick(timers, 5_000).first // "a" finishes
        val r = start(timers, "c", now = 5_000, max = 2)
        assertTrue(r is StartResult.Started)
        assertEquals(listOf("b", "c"), started(r).map { it.id })
    }

    @Test fun rejectsZeroDuration() {
        assertTrue(start(emptyList(), "a", seconds = 0) is StartResult.InvalidDuration)
    }

    @Test fun addMinuteWorksInEveryState() {
        var timers = started(start(emptyList(), "a", seconds = 60, now = 0))
        timers = TimerLogic.addSeconds(timers, "a", 60, 0)
        assertEquals(120_000, timers[0].remainingAt(0))
        timers = TimerLogic.tick(timers, 200_000).first
        timers = TimerLogic.addSeconds(timers, "a", 60, 200_000)
        assertEquals(TimerStatus.RUNNING, timers[0].status)
        assertEquals(60_000, timers[0].remainingAt(200_000))
    }

    @Test fun completesWhileAppWasClosed() {
        // Persisted with an absolute end time; on relaunch an hour later it must be reported finished.
        val timers = started(start(emptyList(), "a", seconds = 300, now = 0))
        val (after, done) = TimerLogic.tick(timers, 3_600_000)
        assertEquals(1, done.size)
        assertEquals(TimerStatus.COMPLETED, after[0].status)
    }
}
