package com.handdraw.studio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.handdraw.studio.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private val isFrontCamera = true

    private val requestCameraPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required for hand tracking", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.drawingView.onStatusChanged = { status ->
            runOnUiThread { updateStatusLabel(status) }
        }

        wireToolbar()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ============================================================
    // Camera + MediaPipe pipeline
    // ============================================================
    private fun startCamera() {
        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            onResult = { result -> runOnUiThread { handleResult(result) } },
            onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        )

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        handLandmarkerHelper?.detect(imageProxy, isFrontCamera)
                            ?: imageProxy.close()
                    }
                }

            val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            binding.drawingView.processHandFrame(null)
            return
        }
        val points = hands[0].map { PointF(it.x(), it.y()) }
        binding.drawingView.processHandFrame(points)
    }

    private fun updateStatusLabel(status: String) {
        binding.statusText.text = status
        binding.statusText.setTextColor(
            when (status) {
                "DRAWING" -> Color.parseColor("#F0BE32")
                "GRABBING" -> Color.parseColor("#32BE6E")
                "READY" -> Color.parseColor("#4187F5")
                "ERASING" -> Color.parseColor("#E14650")
                else -> Color.parseColor("#E14650")
            }
        )
    }

    // ============================================================
    // Toolbar wiring
    // ============================================================
    private fun wireToolbar() = with(binding) {
        btnUndo.setOnClickListener { drawingView.undo() }
        btnRedo.setOnClickListener { drawingView.redo() }
        btnClear.setOnClickListener { drawingView.clear() }
        btnSave.setOnClickListener { saveDrawing() }
        btnEraser.setOnClickListener { drawingView.toggleEraser() }
        btnCanvasBg.setOnClickListener { drawingView.cycleBackground() }
        btnMinus.setOnClickListener { drawingView.changeSize(-2f) }
        btnPlus.setOnClickListener { drawingView.changeSize(2f) }
        btnColors.setOnClickListener { showColorPicker() }
        btnBrush.setOnClickListener { showBrushPicker() }
    }

    private fun showColorPicker() {
        val palette = listOf(
            "Black" to "#14141A", "Red" to "#E13741", "Orange" to "#F57D23",
            "Yellow" to "#EBBE23", "Green" to "#2DBE64", "Cyan" to "#23AFCD",
            "Blue" to "#3773EB", "Purple" to "#9146D2", "Pink" to "#E1419B",
            "Brown" to "#7D4B2D", "Gray" to "#6E737D", "White" to "#FFFFFF"
        )

        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(32, 32, 32, 32)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose color")
            .setView(grid)
            .create()

        for ((name, hex) in palette) {
            val swatch = TextView(this).apply {
                text = ""
                width = 130
                height = 130
                setBackgroundColor(Color.parseColor(hex))
                gravity = Gravity.CENTER
                val params = GridLayout.LayoutParams()
                params.width = 130
                params.height = 130
                params.setMargins(16, 16, 16, 16)
                layoutParams = params
                setOnClickListener {
                    binding.drawingView.setColor(Color.parseColor(hex))
                    dialog.dismiss()
                }
                contentDescription = name
            }
            grid.addView(swatch)
        }
        dialog.show()
    }

    private fun showBrushPicker() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Brush")
            .setView(container)
            .create()

        for (name in Brushes.ORDER) {
            val row = TextView(this).apply {
                text = name
                textSize = 16f
                setPadding(24, 32, 24, 32)
                setOnClickListener {
                    binding.drawingView.setBrush(name)
                    dialog.dismiss()
                }
            }
            container.addView(row)
        }

        val sizeLabel = TextView(this).apply {
            text = "Size: ${binding.drawingView.sizePx.toInt()}px"
            setPadding(24, 32, 24, 8)
        }
        container.addView(sizeLabel)

        val seek = SeekBar(this).apply {
            max = 70
            progress = binding.drawingView.sizePx.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.drawingView.changeSize(value - binding.drawingView.sizePx)
                        sizeLabel.text = "Size: ${value}px"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(seek)

        dialog.show()
    }

    private fun saveDrawing() {
        val bmp = binding.drawingView.exportBitmap()
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "HandDrawStudio")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "drawing_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarkerHelper?.close()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
