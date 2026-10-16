package cc.runtick.app.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import cc.runtick.app.core.*
import kotlinx.coroutines.*
import kotlin.math.sqrt

class ReactionMotion(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var gate = MotionGate()
    private var gyro = 0.0; private var gyroAt = 0L; private var received = false
    private var preflight = "unavailable"; private var gunAt: Double? = null
    private var callback: ((ReactionResult) -> Unit)? = null
    private var timeout: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    suspend fun prepare() {
        stop(); gate = MotionGate(); received = false; preflight = "unavailable"; gyroAt = 0L
        val acceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        val rotation = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        if (!manager.registerListener(this, rotation, 10000) || !manager.registerListener(this, acceleration, 10000)) { stop(); return }
        delay(2200)
        preflight = if (!received) "unavailable" else if (gate.ready) "ready" else "unstable"
    }
    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = sqrt(event.values.take(3).sumOf { it.toDouble() * it.toDouble() })
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) { gyro = magnitude; gyroAt = event.timestamp; return }
        if (gyroAt == 0L || event.timestamp - gyroAt > 200_000_000L) return
        received = true
        val at = MonotonicClock.now() + (event.timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000.0
        gate.sample(at, magnitude, gyro); report()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    fun gun(at: Double, action: (ReactionResult) -> Unit) {
        gunAt = at; callback = action
        if (preflight != "ready") { complete(ReactionResult(null, preflight)); return }
        report()
        if (callback != null) timeout = scope.launch { delay(30000); complete(ReactionResult(null, "timeout")) }
    }
    private fun report() {
        val gun = gunAt ?: return; val move = gate.movedAt ?: return
        if (callback == null) return
        val ms = move - gun
        if (ms !in -120000.0..30000.0) return
        complete(ReactionResult(ms, if (ms < 100) "false_start" else "detected"))
    }
    private fun complete(value: ReactionResult) { val action = callback; stop(); action?.invoke(value) }
    fun stop() { manager.unregisterListener(this); timeout?.cancel(); timeout = null; callback = null; gunAt = null }
}
