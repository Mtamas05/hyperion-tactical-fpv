/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelloBlackboxRecorder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var loggingJob: Job? = null
    private var outputStream: OutputStream? = null
    var isRecording = false
        private set

    fun start(telemetryProvider: () -> TelloTelemetry) {
        if (isRecording) return
        isRecording = true

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "TELLO_BLACKBOX_$timestamp.csv"

        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/TelloBlackbox")
            }

            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

            // CSV Fejléc
            val header = "TimestampMs,BatteryPercent,TempCelsius,PitchDeg,RollDeg,HeightCm,TofCm\n"
            outputStream?.write(header.toByteArray())
            outputStream?.flush()

            val startTime = System.currentTimeMillis()

            loggingJob = scope.launch(Dispatchers.IO) {
                while (isActive && isRecording) {
                    val t = telemetryProvider()
                    val elapsed = System.currentTimeMillis() - startTime
                    val line = "$elapsed,${t.battery},${t.temperatureC},${t.pitch},${t.roll},${t.heightCm},${t.tofCm}\n"

                    try {
                        outputStream?.write(line.toByteArray())
                        outputStream?.flush()
                    } catch (_: Exception) {}

                    delay(100) // 10 Hz mintavételezés
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun stop() {
        isRecording = false
        loggingJob?.cancel()
        loggingJob = null
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
    }
}
