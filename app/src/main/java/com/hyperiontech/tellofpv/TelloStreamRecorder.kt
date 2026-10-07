/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelloStreamRecorder(private val context: Context) {
    @Volatile private var outputStream: java.io.FileOutputStream? = null
    private var tempFile: File? = null

    @Volatile var isRecording = false
        private set

    fun start() {
        if (isRecording) return
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            tempFile = File(context.cacheDir, "REC_$timestamp.h264")

            outputStream = java.io.FileOutputStream(tempFile)
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun writeSampleData(data: ByteArray, offset: Int, length: Int) {
        if (!isRecording) return
        val currentOut = outputStream ?: return
        try {
            currentOut.write(data, offset, length)
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        
        val currentOut = outputStream
        outputStream = null
        
        try {
            currentOut?.flush()
            currentOut?.close()
        } catch (_: Exception) {}

        tempFile?.let { src ->
            if (src.exists() && src.length() > 0) {
                saveToGallery(src)
            }
            src.delete()
        }
        tempFile = null
    }

    private fun saveToGallery(sourceFile: File) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "TELLO_${System.currentTimeMillis()}.h264")
                put(MediaStore.Video.Media.MIME_TYPE, "video/avc")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TelloFPV")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
    }
}
