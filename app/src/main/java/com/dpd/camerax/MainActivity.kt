package com.dpd.camerax

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dpd.camerax.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale

typealias LumaCallback = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var photoCapture: ImageCapture? = null
    private var videoRecorder: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private lateinit var executor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasPermissions()) {
            initializeCamera()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_CODE)
        }

        binding.imageCaptureButton.setOnClickListener { capturePhoto() }
        binding.videoCaptureButton.setOnClickListener { recordVideo() }

        executor = Executors.newSingleThreadExecutor()
    }

    private fun capturePhoto() {
        val capture = photoCapture ?: return

        val name = SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Error capturing photo: ${exc.message}", exc)
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val message = "Photo saved: ${result.savedUri}"
                    Toast.makeText(baseContext, message, Toast.LENGTH_SHORT).show()
                    displayImage(result.savedUri)
                    Log.d(TAG, message)
                }
            }
        )
    }

    private fun displayImage(uri: Uri?) {
        if (uri != null) {
            binding.previewImage.setImageURI(uri)
        } else {
            Toast.makeText(this, "Failed to load image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordVideo() {}

    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            photoCapture = ImageCapture.Builder().build()

            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(executor, BrightnessAnalyzer { luma ->
                    Log.d(TAG, "Luminosity: $luma")
                })
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, photoCapture, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_PATTERN = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PERMISSION_CODE = 10
        private val PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

private class BrightnessAnalyzer(private val callback: LumaCallback) : ImageAnalysis.Analyzer {
    private fun ByteBuffer.toBytes(): ByteArray {
        rewind()
        val array = ByteArray(remaining())
        get(array)
        return array
    }

    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val pixels = buffer.toBytes().map { it.toInt() and 0xFF }
        val averageLuma = pixels.average()
        callback(averageLuma)
        image.close()
    }
}
