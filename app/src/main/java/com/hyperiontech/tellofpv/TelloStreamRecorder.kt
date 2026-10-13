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
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null

    @Volatile var isRecording = false
        private set

    private var isMuxerStarted = false
    private var hasReceivedFirstIdr = false
    private var trackIndex = -1
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var startTimeUs: Long = 0

    fun start() {
        if (isRecording) return
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            tempFile = File(context.cacheDir, "REC_$timestamp.mp4")

            muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
            hasReceivedFirstIdr = false
            trackIndex = -1
            sps = null
            pps = null
            startTimeUs = 0
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun writeSampleData(data: ByteArray, offset: Int, length: Int) {
        if (!isRecording || muxer == null) return

        try {
            if (length >= 5) {
                val nalType = (data[offset + 4].toInt() and 0x1F)

                // Kimentjük az SPS és PPS csomagokat a MediaFormat konfigurációhoz
                if (nalType == 7 || nalType == 8) {
                    if (!isMuxerStarted) {
                        if (nalType == 7) sps = data.copyOfRange(offset, offset + length)
                        if (nalType == 8) pps = data.copyOfRange(offset, offset + length)

                        if (sps != null && pps != null) {
                            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 720)
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
                            
                            trackIndex = muxer!!.addTrack(format)
                            muxer!!.start()
                            isMuxerStarted = true
                            startTimeUs = System.nanoTime() / 1000
                        }
                    }
                    return // Ne írjuk be a muxerbe sima adatként az SPS/PPS csomagokat
                }

                if (isMuxerStarted) {
                    val isKeyFrame = (nalType == 5)
                    if (isKeyFrame) {
                        hasReceivedFirstIdr = true
                    }

                    // Várjunk az első IDR kulcsképkockára a tiszta indulás érdekében
                    if (hasReceivedFirstIdr) {
                        val bufferInfo = MediaCodec.BufferInfo()
                        bufferInfo.size = length
                        bufferInfo.offset = 0
                        bufferInfo.presentationTimeUs = (System.nanoTime() / 1000) - startTimeUs
                        bufferInfo.flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                        val byteBuffer = ByteBuffer.wrap(data, offset, length)
                        muxer!!.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        var muxerStoppedCleanly = false
        try {
            if (isMuxerStarted) {
                muxer?.stop()
                muxerStoppedCleanly = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            muxer = null
            isMuxerStarted = false
        }

        tempFile?.let { src ->
            // Csak akkor mentjük a galériába, ha szabályosan lezárult az MP4
            if (muxerStoppedCleanly && src.exists() && src.length() > 0) {
                saveToGallery(src)
            }
            src.delete()
        }
        tempFile = null
    }

    private fun saveToGallery(sourceFile: File) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "TELLO_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
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
