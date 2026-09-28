package com.spop.poverlay.course

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.overlay.OverlayVisibility
import com.spop.poverlay.ui.theme.MetricCadenceColor
import com.spop.poverlay.ui.theme.MetricPowerColor
import com.spop.poverlay.ui.theme.MetricResistanceColor
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/**
 * Course list and player. The player only displays state; the [CourseService] does the driving,
 * so leaving this screen does not stop the course. Big controls, readable from the saddle.
 */
class CourseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sensor = (application as GrupettoApplication).sensorInterface
        val repository = CourseRepository(this)
        setContent {
            PTONOverlayTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101014)) {
                    val serviceState by CourseService.state.collectAsStateWithLifecycle()
                    var reloadKey by remember { mutableStateOf(0) }
                    val loaded = remember(reloadKey) { repository.load() }
                    val active = serviceState?.takeIf { it.isActive }
                    if (active != null) {
                        PlayerScreen(
                            state = active,
                            power = sensor.power,
                            cadence = sensor.cadence,
                            resistance = sensor.resistance,
                            onPause = { CourseService.pause(this) },
                            onResume = { CourseService.resume(this) },
                            onSkip = { CourseService.skip(this) },
                            onStop = { CourseService.stop(this) }
                        )
                    } else {
                        CourseListScreen(
                            courses = loaded.first,
                            errors = loaded.second,
                            lastState = serviceState,
                            folderPath = repository.folder.absolutePath,
                            onStart = { CourseService.start(this, it.id) },
                            onReload = { reloadKey++ },
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        OverlayVisibility.onScreenStarted(this)
    }

    override fun onStop() {
        super.onStop()
        OverlayVisibility.onScreenStopped(this)
    }
}

private val Heading = Color.White

/** Keeps the bottom controls clear of the minimised stats bar, which sits over this screen. */
private val OverlayClearance = 56.dp
private val Body = Color(0xFFB0B0B8)
private val CardBg = Color(0xFF1C1C22)

@Composable
private fun CourseListScreen(
    courses: List<Course>,
    errors: List<Pair<String, String>>,
    lastState: CourseState?,
    folderPath: String,
    onStart: (Course) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = OverlayClearance)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Courses", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Heading)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onReload) { Text("Reload", color = Heading) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClose) { Text("Close", color = Heading) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Files live in $folderPath", fontSize = 14.sp, color = Body)
        if (lastState != null && lastState.phase == CoursePhase.FINISHED) {
            Spacer(Modifier.height(8.dp))
            Text("Finished: ${lastState.course.name}", fontSize = 18.sp, color = MetricCadenceColor)
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(courses, key = { it.id }) { course ->
                Card(backgroundColor = CardBg, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Heading)
                            val res = course.steps.map { it.resistance }
                            Text(
                                "${course.steps.size} steps, ${CourseParser.formatDuration(course.totalDurationMs)}, " +
                                    "resistance ${res.minOrNull()}-${res.maxOrNull()}",
                                fontSize = 15.sp, color = Body
                            )
                        }
                        Button(
                            onClick = { onStart(course) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MetricResistanceColor),
                            modifier = Modifier.height(56.dp).width(140.dp)
                        ) { Text("Start", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                }
            }
            items(errors) { (file, reason) ->
                Card(backgroundColor = CardBg, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(file, fontSize = 18.sp, color = MetricResistanceColor)
                        Text("Could not read: $reason", fontSize = 14.sp, color = Body)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    state: CourseState,
    power: Flow<Float>,
    cadence: Flow<Float>,
    resistance: Flow<Float>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit
) {
    val livePower by power.collectAsStateWithLifecycle(initialValue = 0f)
    val liveCadence by cadence.collectAsStateWithLifecycle(initialValue = 0f)
    val liveResistance by resistance.collectAsStateWithLifecycle(initialValue = 0f)
    val step = state.step
    val paused = state.phase == CoursePhase.PAUSED

    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = OverlayClearance)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.course.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Heading)
            Spacer(Modifier.weight(1f))
            Text(
                "Step ${state.stepIndex + 1} of ${state.course.steps.size}   " +
                    "${CourseParser.formatDuration(state.totalRemainingMs)} left",
                fontSize = 18.sp, color = Body
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = if (state.course.totalDurationMs > 0)
                state.totalElapsedMs.toFloat() / state.course.totalDurationMs else 0f,
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MetricResistanceColor,
            backgroundColor = CardBg
        )
        Spacer(Modifier.height(20.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Current step: countdown, target, cue
            Card(backgroundColor = CardBg, modifier = Modifier.weight(1.4f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (paused) "PAUSED" else step.label.ifBlank { "Hold" }, fontSize = 24.sp, color = Body)
                    Text(
                        CourseParser.formatDuration(state.stepRemainingMs),
                        fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Heading
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        Target("Resistance", step.resistance.toString(), MetricResistanceColor)
                        step.cadence?.let { Target("Cadence", it.toString(), MetricCadenceColor) }
                    }
                    Spacer(Modifier.height(12.dp))
                    state.nextStep?.let { next ->
                        Text(
                            "Next: ${CourseParser.formatDuration(next.durationMs)} at ${next.resistance}" +
                                (next.cadence?.let { " / $it rpm" } ?: "") +
                                (next.label.takeIf { it.isNotBlank() }?.let { "  $it" } ?: ""),
                            fontSize = 18.sp, color = Body
                        )
                    } ?: Text("Last step", fontSize = 18.sp, color = Body)
                }
            }
            // Live metrics
            Card(backgroundColor = CardBg, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Live("Power", "${livePower.roundToInt()} w", MetricPowerColor)
                    Live("Cadence", "${liveCadence.roundToInt()} rpm", MetricCadenceColor)
                    Live(
                        "Resistance", liveResistance.roundToInt().toString() +
                            (state.commandedResistance?.let { "  (sent $it)" } ?: ""),
                        MetricResistanceColor
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val btn = Modifier.weight(1f).height(72.dp)
            if (paused) {
                Button(onClick = onResume, modifier = btn,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MetricCadenceColor)) {
                    Text("Resume", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                Button(onClick = onPause, modifier = btn,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3A3A44))) {
                    Text("Pause", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Button(onClick = onSkip, modifier = btn,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3A3A44))) {
                Text("Skip step", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(onClick = onStop, modifier = btn,
                colors = ButtonDefaults.buttonColors(backgroundColor = MetricResistanceColor)) {
                Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun Target(name: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, fontSize = 16.sp, color = Body)
        Text(value, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Live(name: String, value: String, color: Color) {
    Column {
        Text(name, fontSize = 16.sp, color = Body)
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
