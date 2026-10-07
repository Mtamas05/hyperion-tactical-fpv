/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class MissionStep(val description: String) {
    object Takeoff : MissionStep("Felszállás és stabilizálás")
    class Hover(val durationMs: Long) : MissionStep("Lebegés (${durationMs / 1000} mp)")
    class Fly(val roll: Int, val pitch: Int, val throttle: Int, val yaw: Int, val durationMs: Long) : 
        MissionStep("Vektorrepülés (${durationMs / 1000} mp)")
    class Panorama360(val speed: Int = 40) : MissionStep("360° Panoráma fordulat")
    class Flip(val direction: FlipDirection) : MissionStep("Szaltó: ${direction.label}")
    object Land : MissionStep("Automatikus leszállás")
}

class TelloMissionEngine(
    private val session: TelloFlightSession,
    private val scope: CoroutineScope
) {
    private var missionJob: Job? = null

    private val _status = MutableStateFlow("Inaktív")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Alapértelmezett bemutató / felmérő autonóm küldetés */
    fun startDemoMission(onPhotoTrigger: () -> Unit) {
        if (_isRunning.value) return

        val plan = listOf(
            MissionStep.Takeoff,
            MissionStep.Hover(3000),
            MissionStep.Panorama360(35),
            MissionStep.Hover(1500),
            MissionStep.Fly(roll = 0, pitch = 25, throttle = 0, yaw = 0, durationMs = 2500),
            MissionStep.Hover(2000),
            MissionStep.Flip(FlipDirection.FORWARD),
            MissionStep.Hover(2000),
            MissionStep.Land
        )

        executePlan(plan, onPhotoTrigger)
    }

    private fun executePlan(steps: List<MissionStep>, onPhotoTrigger: () -> Unit) {
        _isRunning.value = true
        missionJob = scope.launch(Dispatchers.Default) {
            try {
                var isAborted = false
                for ((index, step) in steps.withIndex()) {
                    if (!isActive) break

                    // Fail-Safe: Ha az akku 15% alá esik küldetés közben, azonnal leszállunk
                    val telemetry = session.telemetry.value
                    if (!telemetry.connected) {
                        _status.value = "Kapcsolat megszakadt! Küldetés abortálva."
                        isAborted = true
                        break
                    }
                    if (telemetry.battery in 1..15) {
                        _status.value = "Kritikus akku (${telemetry.battery}%)! Kényszerleszállás..."
                        session.land()
                        isAborted = true
                        break
                    }

                    _status.value = "[${index + 1}/${steps.size}] ${step.description}"

                    when (step) {
                        is MissionStep.Takeoff -> {
                            session.takeOff()
                            delay(5000)
                        }
                        is MissionStep.Hover -> {
                            session.stopAndHover()
                            delay(step.durationMs)
                        }
                        is MissionStep.Fly -> {
                            val interval = 100L
                            val reps = (step.durationMs / interval).toInt()
                            for (i in 0 until reps) {
                                if (!isActive || !session.telemetry.value.connected) break
                                session.sendControl(step.roll, step.pitch, step.throttle, step.yaw, fast = false)
                                delay(interval)
                            }
                            session.stopAndHover()
                        }
                        is MissionStep.Panorama360 -> {
                            val interval = 100L
                            // ~7-8 mp 35-ös yaw értékkel a teljes körbeforduláshoz
                            val duration = 7500L
                            val reps = (duration / interval).toInt()
                            for (i in 0 until reps) {
                                if (!isActive || !session.telemetry.value.connected) break
                                session.sendControl(0, 0, 0, step.speed, fast = false)
                                delay(interval)
                            }
                            session.stopAndHover()
                            onPhotoTrigger()
                            delay(1000)
                        }
                        is MissionStep.Flip -> {
                            session.flip(step.direction)
                            delay(3000)
                        }
                        is MissionStep.Land -> {
                            session.land()
                            delay(4000)
                        }
                    }
                }
                if (!isAborted) {
                    _status.value = "Küldetés befejezve!"
                }
            } catch (_: Exception) {
                _status.value = "Küldetés megszakítva!"
            } finally {
                session.stopAndHover()
                _isRunning.value = false
            }
        }
    }

    fun abort() {
        missionJob?.cancel()
        missionJob = null
        session.stopAndHover()
        _isRunning.value = false
        _status.value = "Megszakítva (Fail-Safe Lebegés)"
    }
}
