/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket

class TelloVideoDecoder(private val scope: CoroutineScope) {
    @Volatile private var codec: MediaCodec? = null
    private var streamJob: Job? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var recorder: TelloStreamRecorder? = null

    fun start(surface: Surface) {
        stop()

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 720).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        streamJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            val nalStream = ByteArrayOutputStream()
            var currentSocket: DatagramSocket? = null

            try {
                currentSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(11111))
                    soTimeout = 3000
                }
                socket = currentSocket

                while (isActive) {
                    try {
                        currentSocket.receive(packet)
                        val data = packet.data
                        val length = packet.length

                        nalStream.write(data, 0, length)
                        val bytes = nalStream.toByteArray()

                        var startIndex = findNalIndex(bytes, 0)
                        while (startIndex != -1) {
                            val nextIndex = findNalIndex(bytes, startIndex + 4)
                            if (nextIndex != -1) {
                                feedCodec(bytes, startIndex, nextIndex - startIndex)
                                startIndex = nextIndex
                            } else {
                                break
                            }
                        }

                        if (startIndex != -1) {
                            val remaining = bytes.copyOfRange(startIndex, bytes.size)
                            nalStream.reset()
                            nalStream.write(remaining)
                        } else if (nalStream.size() > 65536) {
                            nalStream.reset()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                currentSocket?.close()
                if (socket === currentSocket) {
                    socket = null
                }
            }
        }
    }

    private fun feedCodec(data: ByteArray, offset: Int, length: Int) {
        val currentCodec = codec ?: return
        try {
            val inIndex = currentCodec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inBuffer = currentCodec.getInputBuffer(inIndex)
                inBuffer?.clear()
                inBuffer?.put(data, offset, length)
                val pts = System.nanoTime() / 1000
                currentCodec.queueInputBuffer(inIndex, 0, length, pts, 0)

                // Ha van aktív rögzítő, átadjuk a csomagot
                recorder?.let { rec ->
                    if (rec.isRecording) {
                        rec.writeSampleData(data, offset, length)
                    }
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = currentCodec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                currentCodec.releaseOutputBuffer(outIndex, true)
                outIndex = currentCodec.dequeueOutputBuffer(info, 0)
            }
        } catch (_: Exception) {}
    }

    private fun findNalIndex(data: ByteArray, start: Int): Int {
        for (i in start until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        socket?.close()
        socket = null

        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}
