package com.kanpu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTurnTest {
    @Test fun doublePageConsumesEachRealPagesDuration() {
        val timer = AutoTurn(3) { listOf(20_000L, 35_000L, 25_000L)[it] }
        timer.start(0, true, 0)
        assertEquals(TickResult.None, timer.tick(19_999))
        assertEquals(TickResult.FocusChanged(1), timer.tick(20_000))
        val next = timer.tick(55_000) as TickResult.NeedsPage
        assertEquals(2, next.page)
        assertTrue(timer.pageReady(2, next.generation, 55_030))
        assertEquals(TickResult.Finished, timer.tick(80_030))
    }

    @Test fun startingOnRightPageSkipsLeftPageDuration() {
        val timer = AutoTurn(4) { 35_000L }
        timer.start(1, true, 0)
        assertEquals(TickResult.None, timer.tick(34_999))
        assertEquals(2, (timer.tick(35_000) as TickResult.NeedsPage).page)
    }

    @Test fun lateLoadCannotResumeAfterPause() {
        val timer = AutoTurn(2) { 1_000L }
        timer.start(0, false, 0)
        val request = timer.tick(1_000) as TickResult.NeedsPage
        timer.pause(1_100)
        assertFalse(timer.pageReady(1, request.generation, 1_200))
        assertEquals(TurnPhase.PAUSED, timer.phase)
        val again = timer.resume(1_300) as TickResult.NeedsPage
        assertFalse(timer.pageReady(1, request.generation, 1_400))
        assertTrue(timer.pageReady(1, again.generation, 1_400))
    }

    @Test fun startUsesCurrentPagesDurationImmediately() {
        val timer = AutoTurn(1) { 1_000L }
        timer.start(0, false, 5_000)
        assertEquals(TurnPhase.RUNNING, timer.phase)
        assertEquals(1_000, timer.remaining(5_000))
        assertEquals(TickResult.None, timer.tick(5_999))
        assertEquals(TickResult.Finished, timer.tick(6_000))
    }

    @Test fun manualTurnResetsTargetDurationAndAvoidsBoundaryReset() {
        val timer = AutoTurn(2) { 10_000L }
        timer.start(0, false, 0)
        timer.manuallyArrived(0, 5_000)
        assertEquals(5_000, timer.remaining(5_000))
        timer.manuallyArrived(1, 5_000)
        assertEquals(10_000, timer.remaining(5_000))
        assertEquals(TickResult.Finished, timer.tick(15_000))
    }

    @Test fun singlePageCompletesOnlyAfterFullDurationAndDoesNotLoop() {
        val timer = AutoTurn(1) { 1_500L }
        timer.start(0, false, 100)
        assertEquals(TickResult.None, timer.tick(1_599))
        assertEquals(TickResult.Finished, timer.tick(1_600))
        assertEquals(0, timer.focusPage)
        assertEquals(TurnPhase.COMPLETED, timer.phase)
        assertEquals(TickResult.None, timer.tick(10_000))
        timer.pause(10_000)
        assertEquals(TurnPhase.COMPLETED, timer.phase)
    }

    @Test fun loadingFailurePausesAndRetryUsesNewGeneration() {
        val timer = AutoTurn(2) { 1_000L }
        timer.start(0, false, 0)
        val first = timer.tick(1_000) as TickResult.NeedsPage
        timer.loadingFailed(first.generation, 6_000)
        assertEquals(TurnPhase.PAUSED, timer.phase)
        val retry = timer.resume(6_100) as TickResult.NeedsPage
        assertEquals(1, retry.page)
        assertFalse(timer.pageReady(1, first.generation, 6_200))
        assertTrue(timer.pageReady(1, retry.generation, 6_200))
        assertEquals(1_000, timer.remaining(6_200))
    }

    @Test fun activeTimerPausesOnLifecycleButStoppedTimerStaysStopped() {
        val timer = AutoTurn(2) { 5_000L }
        timer.pause(100)
        assertEquals(TurnPhase.STOPPED, timer.phase)
        timer.start(0, false, 0)
        timer.pause(2_000)
        assertEquals(3_000, timer.remaining(20_000))
        assertEquals(TurnPhase.PAUSED, timer.phase)
        timer.resume(20_000)
        assertEquals(TickResult.None, timer.tick(22_999))
        assertEquals(1, (timer.tick(23_000) as TickResult.NeedsPage).page)
    }
}
