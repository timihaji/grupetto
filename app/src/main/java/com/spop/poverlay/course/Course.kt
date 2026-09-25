package com.spop.poverlay.course

/**
 * One timed step of a course: hold [resistance] (Peloton 0-100 scale) for [durationMs].
 * [cadence] is an optional on-screen cue only; the bike cannot enforce it.
 */
data class CourseStep(
    val durationMs: Long,
    val resistance: Int,
    val cadence: Int? = null,
    val label: String = ""
)

data class Course(
    /** File name without extension; used to identify the course. */
    val id: String,
    val name: String,
    val steps: List<CourseStep>
) {
    val totalDurationMs: Long get() = steps.sumOf { it.durationMs }
}

class CourseParseException(val line: Int, message: String) : Exception("Line $line: $message")

/**
 * Plain-text course format, one step per line:
 *
 *     # name: Easy Hills          (optional; otherwise the file name is used)
 *     # duration resistance [cadence] [label]
 *     1:00  30  80  warm up
 *     2:00  45  85  seated climb
 *     90    60      push          (a bare number is seconds)
 *
 * Blank lines and lines starting with # are ignored. Resistance must be 0-100.
 * Cadence, when present, must be a whole number 20-200; anything after it is the label.
 */
object CourseParser {
    private val NAME_DIRECTIVE = Regex("""^#\s*name\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(id: String, text: String, defaultName: String = id): Course {
        var name = defaultName
        val steps = ArrayList<CourseStep>()
        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                NAME_DIRECTIVE.find(line)?.let { name = it.groupValues[1].trim() }
                return@forEachIndexed
            }
            val tokens = line.split(Regex("""\s+"""))
            if (tokens.size < 2) throw CourseParseException(lineNo, "expected 'duration resistance'")
            val durationMs = parseDurationMs(tokens[0])
                ?: throw CourseParseException(lineNo, "bad duration '${tokens[0]}' (use seconds or m:ss)")
            if (durationMs <= 0) throw CourseParseException(lineNo, "duration must be positive")
            val resistance = tokens[1].toIntOrNull()
                ?: throw CourseParseException(lineNo, "bad resistance '${tokens[1]}'")
            if (resistance !in 0..100) throw CourseParseException(lineNo, "resistance must be 0-100")
            var rest = tokens.drop(2)
            var cadence: Int? = null
            rest.firstOrNull()?.toIntOrNull()?.let { c ->
                if (c !in 20..200) throw CourseParseException(lineNo, "cadence must be 20-200")
                cadence = c
                rest = rest.drop(1)
            }
            steps += CourseStep(durationMs, resistance, cadence, rest.joinToString(" "))
        }
        if (steps.isEmpty()) throw CourseParseException(0, "course has no steps")
        return Course(id, name, steps)
    }

    /** "90" -> 90 s, "1:30" -> 90 s, "1:02:03" -> 3723 s. Returns null if malformed. */
    fun parseDurationMs(token: String): Long? {
        val parts = token.split(":")
        if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val seconds = when (parts.size) {
            1 -> parts[0].toLong()
            2 -> parts[0].toLong() * 60 + parts[1].toLong()
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
            else -> return null
        }
        return seconds * 1000
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
