package com.spop.poverlay.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseEngineTest {
    private val course = Course(
        "t", "Test", listOf(
            CourseStep(10_000, 30),
            CourseStep(5_000, 60),
            CourseStep(5_000, 20)
        )
    )

    private class Recorder : CourseEngine.ResistanceSink {
        val sent = ArrayList<Int>()
        override fun setResistance(resistance: Int) { sent += resistance }
    }

    @Test
    fun `first command jumps to target when start resistance unknown`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec)
        eng.start(0)
        assertEquals(listOf(30), rec.sent)
        assertEquals(CoursePhase.RUNNING, eng.state.value.phase)
    }

    @Test
    fun `ramps from known start resistance and never exceeds ramp step per tick`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 5, startResistance = 50)
        eng.start(0)                      // target 30, from 50 -> 45
        eng.tick(250); eng.tick(500); eng.tick(750); eng.tick(1000)
        assertEquals(listOf(45, 40, 35, 30), rec.sent)
        eng.tick(1250)                    // at target: nothing new
        assertEquals(4, rec.sent.size)
        var prev = 50
        for (r in rec.sent) { assertTrue(Math.abs(r - prev) <= 5); prev = r }
    }

    @Test
    fun `advances steps by elapsed time and carries remainder`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 100)
        eng.start(0)
        eng.tick(9_900)
        assertEquals(0, eng.state.value.stepIndex)
        eng.tick(10_300)                  // 300 ms into step 2
        assertEquals(1, eng.state.value.stepIndex)
        assertEquals(300L, eng.state.value.stepElapsedMs)
        assertEquals(listOf(30, 60), rec.sent)
    }

    @Test
    fun `finishes after the last step and sends nothing more`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 100)
        eng.start(0)
        eng.tick(25_000)
        assertEquals(CoursePhase.FINISHED, eng.state.value.phase)
        assertEquals(course.totalDurationMs, eng.state.value.totalElapsedMs)
        val n = rec.sent.size
        eng.tick(30_000)
        assertEquals(n, rec.sent.size)
    }

    @Test
    fun `pause freezes time and resume continues from the same point`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 100)
        eng.start(0)
        eng.tick(4_000)
        eng.pause(5_000)
        assertEquals(CoursePhase.PAUSED, eng.state.value.phase)
        eng.tick(60_000)                  // ignored while paused
        assertEquals(5_000L, eng.state.value.stepElapsedMs)
        eng.resume(100_000)
        eng.tick(101_000)
        assertEquals(6_000L, eng.state.value.stepElapsedMs)
        assertEquals(0, eng.state.value.stepIndex)
    }

    @Test
    fun `skip moves to the next step and finishes from the last`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 100)
        eng.start(0)
        eng.tick(1_000)
        eng.skip(1_000)
        assertEquals(1, eng.state.value.stepIndex)
        assertEquals(10_000L, eng.state.value.totalElapsedMs)
        assertEquals(listOf(30, 60), rec.sent)
        eng.skip(1_000)
        assertEquals(2, eng.state.value.stepIndex)
        eng.skip(1_000)
        assertEquals(CoursePhase.FINISHED, eng.state.value.phase)
    }

    @Test
    fun `stop sends nothing more and is final`() {
        val rec = Recorder()
        val eng = CourseEngine(course, rec, rampStep = 100)
        eng.start(0)
        eng.stop()
        assertEquals(CoursePhase.STOPPED, eng.state.value.phase)
        eng.tick(20_000)
        eng.resume(20_000)
        assertEquals(listOf(30), rec.sent)
        assertEquals(CoursePhase.STOPPED, eng.state.value.phase)
    }

    @Test
    fun `commands are clamped to 0-100 even for a bad course`() {
        val wild = Course("w", "Wild", listOf(CourseStep(1_000, 100), CourseStep(1_000, 0)))
        val rec = Recorder()
        val eng = CourseEngine(wild, rec, rampStep = 100)
        eng.start(0); eng.tick(1_000)
        assertTrue(rec.sent.all { it in 0..100 })
    }
}
