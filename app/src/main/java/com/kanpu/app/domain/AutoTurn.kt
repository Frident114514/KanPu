package com.kanpu.app.domain

enum class TurnPhase { STOPPED, RUNNING, WAITING, PAUSED, COMPLETED }

sealed interface TickResult {
    data object None : TickResult
    data class FocusChanged(val page: Int) : TickResult
    data class NeedsPage(val page: Int, val generation: Long) : TickResult
    data object Finished : TickResult
}

/** Pure state machine. Times are elapsed-realtime milliseconds, supplied by the caller. */
class AutoTurn(
    private val pageCount: Int,
    private val durationMs: (Int) -> Long
) {
    init { require(pageCount > 0) }

    var phase: TurnPhase = TurnPhase.STOPPED
        private set
    var focusPage: Int = 0
        private set
    var deadlineMs: Long = 0
        private set
    var remainingMs: Long = 0
        private set
    var generation: Long = 0
        private set
    var isDouble: Boolean = false
        private set
    private var resumePhase: TurnPhase = TurnPhase.RUNNING
    private var waitingPage: Int? = null

    fun setLayout(double: Boolean, nowMs: Long) {
        if (isDouble == double) return
        pause(nowMs)
        isDouble = double
        generation++
        waitingPage = null
    }

    fun start(page: Int, double: Boolean, nowMs: Long) {
        focusPage = page.coerceIn(0, pageCount - 1)
        isDouble = double
        generation++
        waitingPage = null
        remainingMs = durationMs(focusPage)
        phase = TurnPhase.RUNNING
        deadlineMs = nowMs + remainingMs
    }

    fun stop() {
        generation++
        waitingPage = null
        remainingMs = 0
        phase = TurnPhase.STOPPED
    }

    fun pause(nowMs: Long) {
        if (phase !in setOf(TurnPhase.RUNNING, TurnPhase.WAITING)) return
        resumePhase = phase
        remainingMs = if (phase == TurnPhase.WAITING) 0 else (deadlineMs - nowMs).coerceAtLeast(0)
        phase = TurnPhase.PAUSED
        generation++
        waitingPage = null
    }

    fun resume(nowMs: Long): TickResult {
        if (phase != TurnPhase.PAUSED) return TickResult.None
        if (resumePhase == TurnPhase.WAITING) {
            val next = PageLayout.turn(focusPage, pageCount, isDouble, 1)
            if (next == focusPage) {
                phase = TurnPhase.COMPLETED
                return TickResult.Finished
            }
            waitingPage = next
            phase = TurnPhase.WAITING
            generation++
            return TickResult.NeedsPage(next, generation)
        }
        phase = resumePhase
        deadlineMs = nowMs + remainingMs
        return TickResult.None
    }

    fun manuallyArrived(page: Int, nowMs: Long) {
        val target = page.coerceIn(0, pageCount - 1)
        if (target == focusPage) return
        val before = phase
        generation++
        waitingPage = null
        focusPage = target
        remainingMs = durationMs(target)
        phase = when (before) {
            TurnPhase.RUNNING -> TurnPhase.RUNNING
            TurnPhase.WAITING, TurnPhase.PAUSED -> TurnPhase.PAUSED
            else -> before
        }
        resumePhase = TurnPhase.RUNNING
        deadlineMs = nowMs + remainingMs
    }

    fun currentDurationChanged(nowMs: Long) {
        if (phase in setOf(TurnPhase.STOPPED, TurnPhase.COMPLETED)) return
        pause(nowMs)
        resumePhase = TurnPhase.RUNNING
        remainingMs = durationMs(focusPage)
        deadlineMs = nowMs + remainingMs
    }

    fun remaining(nowMs: Long): Long = when (phase) {
        TurnPhase.RUNNING -> (deadlineMs - nowMs).coerceAtLeast(0)
        else -> remainingMs
    }

    fun tick(nowMs: Long): TickResult {
        if (phase != TurnPhase.RUNNING || nowMs < deadlineMs) return TickResult.None
        if (isDouble && focusPage % 2 == 0 && focusPage + 1 < pageCount) {
            focusPage++
            remainingMs = durationMs(focusPage)
            deadlineMs += remainingMs
            return TickResult.FocusChanged(focusPage)
        }
        if (focusPage == pageCount - 1) {
            phase = TurnPhase.COMPLETED
            remainingMs = 0
            return TickResult.Finished
        }
        val next = PageLayout.turn(focusPage, pageCount, isDouble, 1)
        waitingPage = next
        phase = TurnPhase.WAITING
        generation++
        return TickResult.NeedsPage(next, generation)
    }

    fun pageReady(page: Int, readyGeneration: Long, nowMs: Long): Boolean {
        if (phase != TurnPhase.WAITING || page != waitingPage || readyGeneration != generation) return false
        focusPage = page
        waitingPage = null
        remainingMs = durationMs(page)
        deadlineMs = nowMs + remainingMs
        phase = TurnPhase.RUNNING
        return true
    }

    fun loadingFailed(readyGeneration: Long, nowMs: Long) {
        if (phase == TurnPhase.WAITING && readyGeneration == generation) pause(nowMs)
    }
}
