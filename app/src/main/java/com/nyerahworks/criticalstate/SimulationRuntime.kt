package com.nyerahworks.criticalstate

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.nyerahworks.criticalstate.sim.PlantState
import com.nyerahworks.criticalstate.sim.ReactorSimulator
import kotlin.math.max

/**
 * Owns ReactorSimulator on a dedicated thread.
 *
 * The previous prototype advanced the entire coupled plant from the UI Handler,
 * so thermodynamic property work directly blocked input and drawing.  This
 * runtime keeps every simulator mutation on one worker thread and publishes an
 * immutable PlantState snapshot to the main thread at a deliberately modest
 * cadence.  View code never touches the simulator directly.
 */
class SimulationRuntime(
    private val listener: (PlantState, PerformanceSnapshot) -> Unit,
) {
    data class PerformanceSnapshot(
        val requestedTimeScale: Double,
        val effectiveTimeScale: Double,
        val computeMillis: Double,
        val tickMillis: Double,
        val running: Boolean,
    )

    companion object {
        private const val WALL_QUANTUM_S = 0.10
        private const val TARGET_TICK_MS = 100L
    }

    private val thread = HandlerThread("critical-state-sim", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
        start()
    }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val simulator = ReactorSimulator()

    private var active = false
    private var running = true
    private var timeScale = 1.0
    private var lastWallNanos = 0L
    private var lastPublishedSimulationSeconds = simulator.snapshot().simulationSeconds

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val tickStart = SystemClock.elapsedRealtimeNanos()
            val beforeSim = simulator.snapshot().simulationSeconds

            val state = if (running) {
                simulator.advance(WALL_QUANTUM_S, timeScale)
            } else {
                simulator.snapshot()
            }

            val computeEnd = SystemClock.elapsedRealtimeNanos()
            val computeMillis = (computeEnd - tickStart) / 1_000_000.0
            val wallDeltaSeconds = if (lastWallNanos == 0L) {
                WALL_QUANTUM_S
            } else {
                max(1.0e-6, (computeEnd - lastWallNanos) / 1_000_000_000.0)
            }
            val simulatedDelta = state.simulationSeconds - lastPublishedSimulationSeconds
            val effective = if (running) simulatedDelta / wallDeltaSeconds else 0.0
            val tickMillis = wallDeltaSeconds * 1000.0
            lastWallNanos = computeEnd
            lastPublishedSimulationSeconds = state.simulationSeconds

            val perf = PerformanceSnapshot(
                requestedTimeScale = timeScale,
                effectiveTimeScale = effective,
                computeMillis = computeMillis,
                tickMillis = tickMillis,
                running = running,
            )
            main.post { listener(state, perf) }

            // Schedule from completion instead of queueing overlapping work. If
            // the model is slower than the 100 ms budget the queue remains one
            // item deep rather than building an ever-growing backlog.
            val delay = max(0L, TARGET_TICK_MS - computeMillis.toLong())
            worker.postDelayed(this, delay)
        }
    }

    fun start() {
        worker.post {
            if (active) return@post
            active = true
            lastWallNanos = 0L
            lastPublishedSimulationSeconds = simulator.snapshot().simulationSeconds
            worker.post(tick)
        }
    }

    fun stop() {
        worker.post {
            active = false
            worker.removeCallbacks(tick)
        }
    }

    fun close() {
        active = false
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun setRunning(value: Boolean) = command { running = value }

    fun setTimeScale(value: Double) = command {
        timeScale = when {
            value < 1.0 -> 1.0
            value < 10.0 -> 1.0
            value < 60.0 -> 10.0
            else -> 60.0
        }
    }

    fun setRodInsertion(value: Double) = command { simulator.setRodInsertion(value) }
    fun setTurbineLoad(value: Double) = command { simulator.setTurbineLoad(value) }
    fun setRcpRunning(index: Int, value: Boolean) = command { simulator.setReactorCoolantPump(index, value) }
    fun setBoronMakeup(flowKgS: Double, ppm: Double) = command { simulator.setBoronMakeup(flowKgS, ppm) }
    fun setGeneratorBreakerClosed(value: Boolean) = command { simulator.setGeneratorBreakerClosed(value) }
    fun tripTurbine() = command { simulator.tripTurbine() }
    fun resetTurbineTrip() = command { simulator.resetTurbineTrip() }
    fun tripReactor() = command { simulator.trip() }

    fun resetPlant() = command {
        simulator.reset()
        running = true
        timeScale = 1.0
        lastPublishedSimulationSeconds = simulator.snapshot().simulationSeconds
        lastWallNanos = 0L
    }

    private fun command(action: () -> Unit) {
        worker.post(action)
    }
}
