package com.spop.poverlay.course

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Foreground service that runs a [CourseEngine] against the bike, so a course keeps
 * driving resistance while the rider switches to another app (video, browser).
 * Control it with the static helpers; observe [state].
 */
class CourseService : LifecycleService() {
    companion object {
        private const val ACTION_START = "com.spop.poverlay.course.START"
        private const val ACTION_PAUSE = "com.spop.poverlay.course.PAUSE"
        private const val ACTION_RESUME = "com.spop.poverlay.course.RESUME"
        private const val ACTION_SKIP = "com.spop.poverlay.course.SKIP"
        private const val ACTION_STOP = "com.spop.poverlay.course.STOP"
        private const val EXTRA_COURSE_ID = "course_id"

        private const val NOTIFICATION_ID = 2033
        private const val CHANNEL_ID = "grupetto_course_service"

        /** How often the engine ticks and may send one ramp step to the motor. */
        const val TICK_MS = 250L

        private val mutableState = MutableStateFlow<CourseState?>(null)
        val state: StateFlow<CourseState?> = mutableState

        fun start(context: Context, courseId: String) {
            val intent = Intent(context, CourseService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COURSE_ID, courseId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun skip(context: Context) = send(context, ACTION_SKIP)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, CourseService::class.java).setAction(action))
        }
    }

    private var engine: CourseEngine? = null
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(buildNotification("Course ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_COURSE_ID)?.let { startCourse(it) }
            ACTION_PAUSE -> engine?.pause(now)
            ACTION_RESUME -> engine?.resume(now)
            ACTION_SKIP -> engine?.skip(now)
            ACTION_STOP -> stopCourse()
        }
        return START_NOT_STICKY
    }

    private fun startCourse(courseId: String) {
        cancelCourse()
        val course = CourseRepository(this).find(courseId)
        if (course == null) {
            Timber.e("Course not found: %s", courseId)
            stopSelf()
            return
        }
        val app = application as GrupettoApplication
        val sensor = app.sensorInterface
        lifecycleScope.launch {
            // Seed the ramp from the bike's current resistance so the first change is gentle.
            val current = withTimeoutOrNull(1500) { sensor.resistance.first() }?.toInt()
            val eng = CourseEngine(
                course,
                sink = { r ->
                    Timber.i("Course '%s': set resistance %d", course.name, r)
                    sensor.setResistance(r)
                },
                startResistance = current
            )
            engine = eng
            eng.start(SystemClock.elapsedRealtime())
            tickerJob?.cancel()
            tickerJob = launch {
                while (isActive) {
                    // start() already sent the first ramp step; wait one period before the next
                    delay(TICK_MS)
                    eng.tick(SystemClock.elapsedRealtime())
                    val s = eng.state.value
                    mutableState.value = s
                    updateNotification(s)
                    if (s.phase == CoursePhase.FINISHED || s.phase == CoursePhase.STOPPED) {
                        shutdown()
                        break
                    }
                }
            }
        }
    }

    /** Stop the engine and ticker but keep the service alive (used before starting another course). */
    private fun cancelCourse() {
        engine?.stop()
        tickerJob?.cancel()
        tickerJob = null
        engine?.let { mutableState.value = it.state.value }
        engine = null
    }

    private fun stopCourse() {
        cancelCourse()
        shutdown()
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        tickerJob?.cancel()
        super.onDestroy()
    }

    private fun updateNotification(s: CourseState) {
        val text = when (s.phase) {
            CoursePhase.RUNNING -> "${s.course.name}: step ${s.stepIndex + 1}/${s.course.steps.size}, " +
                "resistance ${s.step.resistance}, ${CourseParser.formatDuration(s.stepRemainingMs)} left"
            CoursePhase.PAUSED -> "${s.course.name}: paused"
            CoursePhase.FINISHED -> "${s.course.name}: finished"
            else -> s.course.name
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Course player", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, CourseActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Grupetto course")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
