/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

class GamepadHandler(
    private val onAxesChanged: (roll: Int, pitch: Int, throttle: Int, yaw: Int) -> Unit,
    private val onTakeoffLand: () -> Unit,
    private val onHover: () -> Unit,
    private val onKillSwitch: () -> Unit,
    private val onSpeedToggle: () -> Unit
) {
    private val deadzone = 0.08f
    private val expo = 0.35f

    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f

    private var l1Pressed = false
    private var r1Pressed = false

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return false
        }

        // Mode 2 kiosztás: 
        // Bal kar: Yaw (X) & Throttle/Magasság (Y)
        // Jobb kar: Roll/Oldal (X) & Pitch/Előre-hátra (Y)
        leftX = applyExpoAndDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        leftY = applyExpoAndDeadzone(-event.getAxisValue(MotionEvent.AXIS_Y))

        rightX = applyExpoAndDeadzone(event.getAxisValue(MotionEvent.AXIS_Z).takeIf { abs(it) > 0.01f } 
            ?: event.getAxisValue(MotionEvent.AXIS_RX))
        rightY = applyExpoAndDeadzone(-(event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { abs(it) > 0.01f } 
            ?: event.getAxisValue(MotionEvent.AXIS_RY)))

        val roll = (rightX * 100).toInt()
        val pitch = (rightY * 100).toInt()
        val throttle = (leftY * 100).toInt()
        val yaw = (leftX * 100).toInt()

        onAxesChanged(roll, pitch, throttle, yaw)
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
            (event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
        ) {
            return false
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> l1Pressed = isDown
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                r1Pressed = isDown
                if (isDown && !l1Pressed) onSpeedToggle()
            }
        }

        // L1 + R1 együttes lenyomása = KILL SWITCH
        if (l1Pressed && r1Pressed) {
            onKillSwitch()
            return true
        }

        if (!isDown) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                onTakeoffLand()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                onHover()
                true
            }
            else -> false
        }
    }

    private fun applyExpoAndDeadzone(raw: Float): Float {
        if (abs(raw) < deadzone) return 0f
        val normalized = (abs(raw) - deadzone) / (1f - deadzone) * sign(raw)
        return (1f - expo) * normalized + expo * normalized.pow(3)
    }
}
