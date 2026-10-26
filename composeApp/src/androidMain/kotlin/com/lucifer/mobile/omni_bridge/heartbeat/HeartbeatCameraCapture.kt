package com.lucifer.mobile.omni_bridge.heartbeat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One bounded still capture. The caller must already hold runtime CAMERA consent
 * and run from an opted-in camera-type foreground service. Captured bytes are not
 * persisted here; the session engine forwards them to its configured vision model. */
class HeartbeatCameraCapture(context: Context) {
    private val appContext = context.applicationContext

    suspend fun captureJpeg(): ByteArray = suspendCancellableCoroutine { continuation ->
        val thread = HandlerThread("LuciferHeartbeatCamera").apply { start() }
        val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)
        val cameraRef = AtomicReference<CameraDevice?>(null)
        val readerRef = AtomicReference<ImageReader?>(null)
        val sessionRef = AtomicReference<CameraCaptureSession?>(null)

        fun cleanup() {
            sessionRef.getAndSet(null)?.close()
            cameraRef.getAndSet(null)?.close()
            readerRef.getAndSet(null)?.close()
            thread.quitSafely()
        }

        fun fail(error: Throwable) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                continuation.resumeWith(Result.failure(error))
            }
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) cleanup()
        }

        try {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("No camera is available")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = sizes.minByOrNull { kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720) }
                ?: Size(1280, 720)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            readerRef.set(reader)
            reader.setOnImageAvailableListener({ source ->
                if (finished.get()) return@setOnImageAvailableListener
                val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                val jpeg = try {
                    val buffer = image.planes.first().buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    normalizeJpeg(bytes)
                } catch (error: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (jpeg == null || jpeg.isEmpty()) fail(IllegalStateException("Camera returned an empty image"))
                else if (finished.compareAndSet(false, true)) {
                    cleanup()
                    continuation.resumeWith(Result.success(jpeg))
                }
            }, handler)

            handler.postDelayed({ fail(IllegalStateException("Camera capture timed out")) }, CAPTURE_TIMEOUT_MS)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (finished.get()) { camera.close(); return }
                    cameraRef.set(camera)
                    try {
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (finished.get()) { session.close(); return }
                                sessionRef.set(session)
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                                            set(CaptureRequest.JPEG_ORIENTATION, it)
                                        }
                                    }.build()
                                    session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: CameraCaptureSession.CaptureFailure
                                        ) = fail(IllegalStateException("Camera capture failed: ${failure.reason}"))
                                    }, handler)
                                } catch (error: Exception) { fail(error) }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                fail(IllegalStateException("Camera capture session could not be configured"))
                            }
                        }, handler)
                    } catch (error: Exception) { fail(error) }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail(IllegalStateException("Camera error $error"))
                }
            }, handler)
        } catch (error: Exception) {
            fail(error)
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 12_000L
        const val MAX_JPEG_BYTES = 1_900_000

        fun normalizeJpeg(source: ByteArray): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                if (source.size <= MAX_JPEG_BYTES) return source
                throw IllegalStateException("Could not decode camera image")
            }
            for (sample in listOf(1, 2, 4, 8)) {
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
                    ?: continue
                val maxSide = maxOf(decoded.width, decoded.height)
                val scale = minOf(1.0, 1280.0 / maxSide)
                val outputBitmap = if (scale < 1.0) Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                ) else decoded
                if (outputBitmap !== decoded) decoded.recycle()
                try {
                    for (quality in listOf(82, 72, 62, 52, 42)) {
                        val output = ByteArrayOutputStream()
                        outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                        if (output.size() <= MAX_JPEG_BYTES) return output.toByteArray()
                    }
                } finally {
                    outputBitmap.recycle()
                }
            }
            throw IllegalStateException("Camera image could not be reduced below 2 MB")
        }
    }
}
