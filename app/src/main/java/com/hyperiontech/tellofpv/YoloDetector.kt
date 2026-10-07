/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class YoloDetector(context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 320

    init {
        try {
            val assetFileDescriptor = context.assets.openFd("yolo11n_320_float32.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val modelBuffer = fileInputStream.channel.use { fileChannel ->
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
            assetFileDescriptor.close()
            fileInputStream.close()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val output = Array(1) { Array(84) { FloatArray(2100) } }
    
    private val imageProcessor = org.tensorflow.lite.support.image.ImageProcessor.Builder()
        .add(org.tensorflow.lite.support.image.ops.ResizeOp(inputSize, inputSize, org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR))
        .add(org.tensorflow.lite.support.common.ops.NormalizeOp(0f, 255f))
        .build()
        
    private var tensorImage = org.tensorflow.lite.support.image.TensorImage(org.tensorflow.lite.DataType.FLOAT32)

    fun detect(bitmap: Bitmap): TrackedTarget? {
        val currentInterpreter = interpreter ?: return null

        tensorImage.load(bitmap)
        val processedImage = imageProcessor.process(tensorImage)

        // YOLO kimeneti tömb [1, 84, 2100] formátumhoz
        currentInterpreter.run(processedImage.buffer, output)

        var bestConf = 0.45f
        var bestBox: RectF? = null

        // Célpont (személy - class 0) megkeresése a detekciók között
        for (i in 0 until 2100) {
            val conf = output[0][4][i] // Első osztály (person) valószínűsége
            if (conf > bestConf) {
                bestConf = conf
                val cx = output[0][0][i] / inputSize
                val cy = output[0][1][i] / inputSize
                val w = output[0][2][i] / inputSize
                val h = output[0][3][i] / inputSize

                bestBox = RectF(
                    (cx - w / 2f).coerceIn(0f, 1f),
                    (cy - h / 2f).coerceIn(0f, 1f),
                    (cx + w / 2f).coerceIn(0f, 1f),
                    (cy + h / 2f).coerceIn(0f, 1f)
                )
            }
        }

        return bestBox?.let { TrackedTarget(it, bestConf, "Személy") }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
