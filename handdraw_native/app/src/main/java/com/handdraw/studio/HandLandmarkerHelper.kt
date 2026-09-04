package com.handdraw.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Thin wrapper around MediaPipe's HandLandmarker task, running in
 * LIVE_STREAM mode against the CameraX preview. This is the real,
 * 21-point-per-hand landmark model — the same family of model used
 * by the original desktop app, just accessed through the officially
 * supported Android (Kotlin) API instead of the Python one.
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val onResult: (result: HandLandmarkerResult) -> Unit,
    private val onError: (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null

    init {
        setup()
    }

    private fun setup() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult, _: MPImage -> onResult(result) }
                .setErrorListener { e -> onError(e.message ?: "MediaPipe error") }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker", e)
            onError("Failed to load hand-tracking model: ${e.message}")
        }
    }

    /**
     * Feed one CameraX frame. Must be called with an ImageAnalysis
     * configured for OUTPUT_IMAGE_FORMAT_RGBA_8888. Closes the
     * ImageProxy itself — caller must not close it again.
     */
    fun detect(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, bitmapBuffer.width / 2f, bitmapBuffer.height / 2f)
            }
        }

        val rotated = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
        )

        val mpImage: MPImage = BitmapImageBuilder(rotated).build()
        landmarker.detectAsync(mpImage, System.currentTimeMillis())
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }

    companion object {
        private const val TAG = "HandLandmarkerHelper"
        // Placed in app/src/main/assets/hand_landmarker.task by the CI
        // workflow (or manually — see README) before building.
        private const val MODEL_PATH = "hand_landmarker.task"
    }
}
