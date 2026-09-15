package io.github.anbu00001.nocturne.collector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Spec §4.2: a `specialUse` foreground service that reads the light sensor only while the screen is on. Screen
 * on and off broadcasts cannot be declared in the manifest, which is the only reason a live process is needed.
 * A foreground service is also what lets the sensor deliver events at all: since Android 9, apps in the
 * background get none. When ColorOS kills it only lux is lost; usage still comes from the harvester, and
 * [LightService] restarts it at the next chance.
 */
class LightSamplerService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sampler: LightSampler
    private lateinit var sensors: SensorManager
    private var lightSensor: Sensor? = null
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = sampler.onLux(System.currentTimeMillis(), event.values[0])
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn()
                Intent.ACTION_SCREEN_OFF -> screenOff()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            sampler.tick(System.currentTimeMillis())
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        thread = HandlerThread("nocturne-light").apply { start() }
        handler = Handler(thread.looper)
        val host = application as CollectorHost
        val display = AndroidDisplayState(this)
        sampler = LightSampler(
            display = display::snapshot,
            brightnessMax = DeviceProfile(this).displayProfile().brightnessSettingMax,
            write = { row -> writes.launch { host.database.light().insert(row) } },
        )
        sensors = getSystemService(SensorManager::class.java)
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screen, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (getSystemService(PowerManager::class.java).isInteractive) handler.post { screenOn() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: IllegalStateException) {
            // A sticky restart from the background can be refused (ForegroundServiceStartNotAllowedException).
            LightService.recordRefusal(this, e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        handler.post { screenOff() }
        thread.quitSafely()
        running = false
        super.onDestroy()
    }

    private fun screenOn() {
        if (sampler.isSampling) return
        sampler.screenOn(System.currentTimeMillis())
        lightSensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        handler.postDelayed(tick, TICK_MS)
    }

    private fun screenOff() {
        handler.removeCallbacks(tick)
        sensors.unregisterListener(listener)
        sampler.screenOff(System.currentTimeMillis())
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Tone.Light.CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN).apply {
                description = Tone.Light.CHANNEL_DESCRIPTION
                setShowBadge(false)
            },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(Tone.Light.NOTIFICATION_TITLE)
            .setContentText(Tone.Light.NOTIFICATION_TEXT)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        /** Whether the service is alive in this process. */
        @Volatile
        var running = false
            private set

        private const val CHANNEL_ID = "light"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 15_000L
    }
}
