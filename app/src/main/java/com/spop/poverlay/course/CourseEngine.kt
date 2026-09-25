package com.spop.poverlay.course

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CoursePhase { IDLE, RUNNING, PAUSED, FINISHED, STOPPED }

data class CourseState(
    val course: Course,
    val phase: CoursePhase = CoursePhase.IDLE,
    val stepIndex: Int = 0,
    val stepElapsedMs: Long = 0,
    val totalElapsedMs: Long = 0,
    /** Last resistance actually sent to the bike, or null if nothing sent yet. */
    val commandedResistance: Int? = null
) {
    val step: CourseStep get() = course.steps[stepIndex.coerceIn(0, course.steps.lastIndex)]
    val nextStep: CourseStep? get() = course.steps.getOrNull(stepIndex + 1)
    val stepRemainingMs: Long get() = (step.durationMs - stepElapsedMs).coerceAtLeast(0)
    val totalRemainingMs: Long get() = (course.totalDurationMs - totalElapsedMs).coerceAtLeast(0)
    val isActive: Boolean get() = phase == CoursePhase.RUNNING || phase == CoursePhase.PAUSED
}

/**
 * Pure step scheduler. No Android dependencies, so it is unit-testable.
 *
 * Call [tick] regularly with a monotonic clock in milliseconds. Resistance is sent through
 * [sink] only when the commanded value changes, and never after [stop] or once finished.
 *
 * Safety rails: every command is clamped to 0-100, and the commanded value moves toward the
 * step target by at most [rampStep] per tick, so a bad course file cannot slam the motor.
 * The tick period is chosen by the caller; see [CourseService] for the value used on the bike.
 */
class CourseEngine(
    course: Course,
    private val sink: ResistanceSink,
    private val rampStep: Int = DEFAULT_RAMP_STEP,
    /** Bike's current resistance when the course starts, so the first change ramps too. */
    private val startResistance: Int? = null
) {
    fun interface ResistanceSink {
        fun setResistance(resistance: Int)
    }

    companion object {
        const val DEFAULT_RAMP_STEP = 5
    }

    private val mutableState = MutableStateFlow(CourseState(course))
    val state: StateFlow<CourseState> = mutableState

    private var lastTickMs: Long = 0
    private var commanded: Int? = startResistance?.coerceIn(0, 100)

    fun start(nowMs: Long) {
        val s = mutableState.value
        if (s.phase != CoursePhase.IDLE) return
        lastTickMs = nowMs
        mutableState.value = s.copy(phase = CoursePhase.RUNNING, stepIndex = 0, stepElapsedMs = 0, totalElapsedMs = 0)
        drive()
    }

    fun pause(nowMs: Long) {
        val s = mutableState.value
        if (s.phase != CoursePhase.RUNNING) return
        tick(nowMs)
        mutableState.value = mutableState.value.copy(phase = CoursePhase.PAUSED)
    }

    fun resume(nowMs: Long) {
        val s = mutableState.value
        if (s.phase != CoursePhase.PAUSED) return
        lastTickMs = nowMs
        mutableState.value = s.copy(phase = CoursePhase.RUNNING)
    }

    /** Jump to the start of the next step (or finish if on the last one). */
    fun skip(nowMs: Long) {
        val s = mutableState.value
        if (!s.isActive) return
        lastTickMs = nowMs
        val consumedSoFar = s.course.steps.take(s.stepIndex).sumOf { it.durationMs }
        val nextIndex = s.stepIndex + 1
        if (nextIndex > s.course.steps.lastIndex) {
            finish()
            return
        }
        mutableState.value = s.copy(
            stepIndex = nextIndex,
            stepElapsedMs = 0,
            totalElapsedMs = consumedSoFar + s.step.durationMs
        )
        drive()
    }

    /** Stop the course. Resistance stays wherever it is; the rider has the knob. */
    fun stop() {
        val s = mutableState.value
        if (s.phase == CoursePhase.FINISHED || s.phase == CoursePhase.STOPPED) return
        mutableState.value = s.copy(phase = CoursePhase.STOPPED)
    }

    fun tick(nowMs: Long) {
        val s = mutableState.value
        if (s.phase != CoursePhase.RUNNING) return
        val dt = (nowMs - lastTickMs).coerceAtLeast(0)
        lastTickMs = nowMs

        var stepIndex = s.stepIndex
        var stepElapsed = s.stepElapsedMs + dt
        var totalElapsed = s.totalElapsedMs + dt
        val steps = s.course.steps
        while (stepElapsed >= steps[stepIndex].durationMs) {
            stepElapsed -= steps[stepIndex].durationMs
            if (stepIndex == steps.lastIndex) {
                mutableState.value = s.copy(
                    stepIndex = stepIndex,
                    stepElapsedMs = steps[stepIndex].durationMs,
                    totalElapsedMs = s.course.totalDurationMs
                )
                finish()
                return
            }
            stepIndex++
        }
        mutableState.value = s.copy(
            stepIndex = stepIndex,
            stepElapsedMs = stepElapsed,
            totalElapsedMs = totalElapsed.coerceAtMost(s.course.totalDurationMs)
        )
        drive()
    }

    private fun finish() {
        mutableState.value = mutableState.value.copy(phase = CoursePhase.FINISHED)
    }

    /** Move the commanded resistance one ramp step toward the current target and send it. */
    private fun drive() {
        val s = mutableState.value
        if (s.phase != CoursePhase.RUNNING) return
        val target = s.step.resistance.coerceIn(0, 100)
        val current = commanded
        val next = when {
            current == null -> target
            target > current -> (current + rampStep).coerceAtMost(target)
            target < current -> (current - rampStep).coerceAtLeast(target)
            else -> current
        }
        if (next != current) {
            commanded = next
            sink.setResistance(next)
            mutableState.value = mutableState.value.copy(commandedResistance = next)
        }
    }
}
