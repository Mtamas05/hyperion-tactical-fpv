/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class TrackedTarget(
    val rect: RectF,
    val confidence: Float,
    val label: String
)

class TelloActiveTracker(
    private val session: TelloFlightSession
) {
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _currentTarget = MutableStateFlow<TrackedTarget?>(null)
    val currentTarget: StateFlow<TrackedTarget?> = _currentTarget.asStateFlow()

    // PID paraméterek
    private val kpYaw = 60f
    private val kiYaw = 5f
    private val kdYaw = 20f

    private val kpThrottle = 50f
    private val kiThrottle = 4f
    private val kdThrottle = 15f

    // Állapotváltozók a PID-hez
    private var lastErrorX = 0f
    private var lastErrorY = 0f
    private var integralX = 0f
    private var integralY = 0f
    private var lastTimeMs = 0L

    // Holtzóna és anti-windup
    private val centerDeadzone = 0.08f
    private val integralLimit = 20f

    fun startTracking() {
        _isTracking.value = true
        resetPID()
    }

    fun stopTracking() {
        _isTracking.value = false
        _currentTarget.value = null
        resetPID()
        session.stopAndHover()
    }

    private fun resetPID() {
        integralX = 0f
        integralY = 0f
        lastErrorX = 0f
        lastErrorY = 0f
        lastTimeMs = System.currentTimeMillis()
    }

    fun onFrameDetection(target: TrackedTarget?) {
        if (!_isTracking.value) return
        if (!session.telemetry.value.connected) {
            stopTracking()
            return
        }
        _currentTarget.value = target

        val currentTime = System.currentTimeMillis()
        val rawDt = if (lastTimeMs > 0L) (currentTime - lastTimeMs) / 1000f else 0.1f
        val dt = rawDt.coerceAtLeast(0.001f)
        lastTimeMs = currentTime

        if (target == null) {
            // Célpont elveszett: lebegünk és nullázzuk a felgyűlt hibát (I, D)
            resetPID()
            session.stopAndHover()
            return
        }

        val centerX = (target.rect.left + target.rect.right) / 2f
        val centerY = (target.rect.top + target.rect.bottom) / 2f

        // Eltérés a kép középpontjától (-0.5 .. +0.5)
        val errorX = centerX - 0.5f
        val errorY = centerY - 0.5f

        // Yaw PID (X tengely)
        var yawCorrection = 0
        if (abs(errorX) > centerDeadzone) {
            integralX += errorX * dt
            integralX = integralX.coerceIn(-integralLimit, integralLimit)
            val derivativeX = (errorX - lastErrorX) / dt
            yawCorrection = (kpYaw * errorX + kiYaw * integralX + kdYaw * derivativeX).toInt().coerceIn(-40, 40)
        } else {
            integralX = 0f // Deadzone-ban nullázzuk az integrált
        }
        lastErrorX = errorX

        // Throttle PID (Y tengely)
        var throttleCorrection = 0
        if (abs(errorY) > centerDeadzone) {
            // Y tengely: felfelé emelkedés (negatív hiba -> negatív nyers érték, de az RC-ben a negatív Y a lefelé haladás?
            // "Ha a célpont fentebb van (kisebb cy -> negatív errorY), emelkednünk kell."
            // Tehát az input negatív errorY-ra pozitív throttle kell:
            val invertedErrorY = -errorY
            integralY += invertedErrorY * dt
            integralY = integralY.coerceIn(-integralLimit, integralLimit)
            val derivativeY = (invertedErrorY - lastErrorY) / dt
            throttleCorrection = (kpThrottle * invertedErrorY + kiThrottle * integralY + kdThrottle * derivativeY).toInt().coerceIn(-30, 30)
            lastErrorY = invertedErrorY
        } else {
            integralY = 0f
            lastErrorY = 0f
        }

        // Automatikus korrekciós parancs küldése
        session.sendControl(
            leftRight = 0,
            forwardBack = 0,
            upDown = throttleCorrection,
            yaw = yawCorrection,
            fast = true
        )
    }
}
