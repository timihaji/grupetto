package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.course.CourseParser
import com.spop.poverlay.course.CoursePhase
import com.spop.poverlay.course.CourseState
import com.spop.poverlay.ui.theme.MetricCadenceColor
import com.spop.poverlay.ui.theme.MetricResistanceColor

private val StripBackground = Color(0xFF2B2452)
private val StripDim = Color(0xFFB9B4D6)

/**
 * Compact course readout for the always-visible overlay tab, so a course can be followed while
 * another app (video, browser) is in front. Shows: step countdown, step label, target resistance,
 * cadence cue and the next step's resistance. Tap opens the full player with its controls.
 */
@Composable
fun OverlayCourseStrip(state: CourseState, onTap: () -> Unit) {
    val step = state.step
    val paused = state.phase == CoursePhase.PAUSED
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(StripBackground, RoundedCornerShape(6.dp))
            .clickable { onTap() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = if (paused) "II" else CourseParser.formatDuration(state.stepRemainingMs),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
        if (paused) {
            Spacer(Modifier.width(6.dp))
            Text(
                CourseParser.formatDuration(state.stepRemainingMs),
                color = StripDim, fontSize = 17.sp
            )
        }
        if (step.label.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                step.label,
                color = StripDim,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("R", color = StripDim, fontSize = 13.sp)
        Spacer(Modifier.width(2.dp))
        Text(
            step.resistance.toString(),
            color = MetricResistanceColor,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
        step.cadence?.let {
            Spacer(Modifier.width(8.dp))
            Text("C", color = StripDim, fontSize = 13.sp)
            Spacer(Modifier.width(2.dp))
            Text(it.toString(), color = MetricCadenceColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = state.nextStep?.let { "next ${it.resistance}" } ?: "last",
            color = StripDim,
            fontSize = 15.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${state.stepIndex + 1}/${state.course.steps.size}",
            color = StripDim,
            fontSize = 13.sp
        )
    }
}
