package com.spop.poverlay.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CourseParserTest {
    @Test
    fun `parses durations in seconds, m-ss and h-mm-ss`() {
        assertEquals(90_000L, CourseParser.parseDurationMs("90"))
        assertEquals(90_000L, CourseParser.parseDurationMs("1:30"))
        assertEquals(3_723_000L, CourseParser.parseDurationMs("1:02:03"))
        assertNull(CourseParser.parseDurationMs("1:2:3:4"))
        assertNull(CourseParser.parseDurationMs("abc"))
        assertNull(CourseParser.parseDurationMs("1:"))
    }

    @Test
    fun `parses a course with name, comments, optional cadence and labels`() {
        val text = """
            # name: Test Ride
            # duration resistance cadence label

            1:00 30 80 warm up
            90   55     push hard
            0:30 40
        """.trimIndent()
        val course = CourseParser.parse("test_ride", text)
        assertEquals("Test Ride", course.name)
        assertEquals(3, course.steps.size)
        assertEquals(CourseStep(60_000, 30, 80, "warm up"), course.steps[0])
        assertEquals(CourseStep(90_000, 55, null, "push hard"), course.steps[1])
        assertEquals(CourseStep(30_000, 40, null, ""), course.steps[2])
        assertEquals(180_000L, course.totalDurationMs)
    }

    @Test
    fun `falls back to default name`() {
        val course = CourseParser.parse("hills", "1:00 30", defaultName = "hills")
        assertEquals("hills", course.name)
    }

    @Test
    fun `rejects resistance outside 0-100 with the line number`() {
        try {
            CourseParser.parse("x", "1:00 30\n1:00 120")
            fail("expected exception")
        } catch (e: CourseParseException) {
            assertEquals(2, e.line)
        }
    }

    @Test
    fun `rejects missing resistance, bad duration, zero duration and empty course`() {
        for (bad in listOf("1:00", "x 30", "0 30", "# only a comment")) {
            try {
                CourseParser.parse("x", bad)
                fail("expected exception for '$bad'")
            } catch (_: CourseParseException) {
            }
        }
    }

    @Test
    fun `formats durations`() {
        assertEquals("0:05", CourseParser.formatDuration(4_100))
        assertEquals("1:30", CourseParser.formatDuration(90_000))
        assertEquals("1:00:00", CourseParser.formatDuration(3_600_000))
    }
}
