package com.spop.poverlay.sensor.interfaces

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/**
 * Emulator stand-in for a Bike+ that honours [setResistance]. Cadence wobbles around 80 rpm,
 * power is a rough Peloton-like function of resistance and cadence, resistance moves toward
 * the commanded value a few points per update to mimic the motor.
 */
class SimulatedBikeSensorInterface : SensorInterface {
    private val commanded = MutableStateFlow(30)
    private var actual = 30f
    private var t = 0

    override fun setResistance(resistance: Int) {
        commanded.value = resistance.coerceIn(0, 100)
    }

    private fun sample(): Triple<Float, Float, Float> {
        t++
        val target = commanded.value.toFloat()
        actual += (target - actual).coerceIn(-4f, 4f)
        val cadence = 80f + 3f * sin(t / 7.0).toFloat()
        val power = cadence * (0.2f + 0.03f * actual) + 2f * sin(t / 3.0).toFloat()
        return Triple(power, cadence, actual)
    }

    private val ticks: Flow<Triple<Float, Float, Float>> = flow {
        while (true) {
            emit(sample())
            delay(200)
        }
    }

    override val power: Flow<Float> get() = flow { ticks.collect { emit(it.first) } }
    override val cadence: Flow<Float> get() = flow { ticks.collect { emit(it.second) } }
    override val resistance: Flow<Float> get() = flow { ticks.collect { emit(it.third) } }
}
