/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.Context
import android.graphics.RectF
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TelloIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test TelloFlightSession telemetry parsing`() {
        val mockContext = mockk<Context>(relaxed = true)
        val session = TelloFlightSession(mockContext)

        val rawData = mapOf(
            "pitch" to 10,
            "roll" to -5,
            "bat" to 85,
            "h" to 12
        )

        session.onTelemetry(rawData)

        val telemetry = session.telemetry.value
        assertEquals(10, telemetry.pitch)
        assertEquals(-5, telemetry.roll)
        assertEquals(85, telemetry.battery)
        assertEquals(120, telemetry.heightCm) // 12 dm -> 120 cm
        assertTrue(telemetry.connected)
    }

    @Test
    fun `test TelloActiveTracker PID logic with lost target`() {
        val session = mockk<TelloFlightSession>(relaxed = true)
        val tracker = TelloActiveTracker(session)

        tracker.startTracking()
        assertTrue(tracker.isTracking.value)

        // Első detekció (target lost)
        tracker.onFrameDetection(null)

        // Assert hogy stopAndHover meghívásra került
        verify { session.stopAndHover() }

        tracker.stopTracking()
        assertFalse(tracker.isTracking.value)
    }

    @Test
    fun `test TelloActiveTracker PID logic with valid target offsets`() {
        val session = mockk<TelloFlightSession>(relaxed = true)
        val tracker = TelloActiveTracker(session)

        tracker.startTracking()

        // Mivel már van Robolectric, a valódi RectF osztályt tudjuk használni
        val rect = RectF(0.8f, 0.0f, 1.0f, 0.2f)
        val target = TrackedTarget(rect, 0.9f, "Person")

        // Első frame (csak inicializálja az időt)
        tracker.onFrameDetection(target)
        
        clearMocks(session, answers = false)
        
        // Második frame (dt kiszámításhoz egy kis idő eltelik)
        Thread.sleep(50)
        tracker.onFrameDetection(target)

        val yawSlot = slot<Int>()
        val throttleSlot = slot<Int>()
        verify(exactly = 1) { 
            session.sendControl(
                leftRight = 0, 
                forwardBack = 0, 
                upDown = capture(throttleSlot), 
                yaw = capture(yawSlot), 
                fast = false
            ) 
        }

        // Cél jobbra van -> pozitív yaw
        assertTrue("Yaw kéne hogy pozitív legyen a jobbra téréshez", yawSlot.captured > 0)
        
        // Cél fent van -> pozitív throttle
        assertTrue("Throttle kéne hogy pozitív legyen az emelkedéshez", throttleSlot.captured > 0)
    }

    @Test
    fun `test TelloMissionEngine executes plan and fail-safe`() = testScope.runTest {
        val session = mockk<TelloFlightSession>(relaxed = true)
        
        val telemetryFlow = MutableStateFlow(TelloTelemetry(battery = 10))
        every { session.telemetry } returns telemetryFlow

        val missionEngine = TelloMissionEngine(session, testScope)

        var photoTriggered = false
        missionEngine.startDemoMission(onPhotoTrigger = { photoTriggered = true })
        
        // Előre tekerjük az időt, hogy a coroutine lefusson
        testScheduler.advanceUntilIdle()
        
        // Should trigger land() due to fail-safe (battery < 15)
        verify { session.land() }
        
        assertFalse(missionEngine.isRunning.value)
        assertTrue(missionEngine.status.value.contains("Kritikus akku"))
    }
}
