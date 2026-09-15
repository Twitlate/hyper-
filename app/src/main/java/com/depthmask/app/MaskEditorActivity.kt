package com.depthmask.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.depthmask.app.databinding.ActivityMaskEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

class MaskEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMaskEditorBinding
    private var originalBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var displayBitmap: Bitmap? = null
    private var currentTool = Tool.CROP
    private var cropPosition = 0.5f
    private var brushSize = 50f
    private var isErasing = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private enum class Tool { CROP, BRUSH_ERASE, BRUSH_RESTORE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaskEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadImages()
        setupUI()
        setupTouchHandling()
    }

    private fun loadImages() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE

                val bitmapUri = intent.getStringExtra("bitmap_uri")
                val maskData = intent.getByteArrayExtra("mask_data")
                val maskWidth = intent.getIntExtra("mask_width", 0)
                val maskHeight = intent.getIntExtra("mask_height", 0)

                if (bitmapUri == null || maskData == null) {
                    Toast.makeText(this@MaskEditorActivity, "Missing image data", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                originalBitmap = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(bitmapUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                }

                maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(maskWidth * maskHeight)

                for (i in pixels.indices) {
                    val byteIndex = i * 4
                    val confidence = java.nio.ByteBuffer.wrap(maskData, byteIndex, 4).float
                    val alpha = (confidence * 255).toInt().coerceIn(0, 255)
                    pixels[i] = Color.argb(alpha, 255, 255, 255)
                }

                maskBitmap?.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                applyCropAndDisplay()
                binding.progressBar.visibility = View.GONE

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MaskEditorActivity, "Failed to load", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
                finish()
            }
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnToolCrop.setOnClickListener {
            currentTool = Tool.CROP
            updateToolSelection()
            binding.cropSeekBar.visibility = View.VISIBLE
            binding.brushSizeSeekBar.visibility = View.GONE
        }

        binding.btnToolErase.setOnClickListener {
            currentTool = Tool.BRUSH_ERASE
            updateToolSelection()
            binding.cropSeekBar.visibility = View.GONE
            binding.brushSizeSeekBar.visibility = View.VISIBLE
        }

        binding.btnToolRestore.setOnClickListener {
            currentTool = Tool.BRUSH_RESTORE
            updateToolSelection()
            binding.cropSeekBar.visibility = View.GONE
            binding.brushSizeSeekBar.visibility = View.VISIBLE
        }

        binding.cropSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    cropPosition = progress / 100f
                    applyCropAndDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.brushSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    brushSize = (progress + 10).toFloat()
                    binding.brushSizeLabel.text = "Size: ${brushSize.toInt()}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnReset.setOnClickListener {
            maskBitmap?.recycle()
            loadImages()
        }

        binding.btnSave.setOnClickListener {
            saveEditedMask()
        }

        binding.cropSeekBar.progress = 50
        binding.brushSizeSeekBar.progress = 40
        binding.brushSizeLabel.text = "Size: 50"

        updateToolSelection()
    }

    private fun updateToolSelection() {
        binding.btnToolCrop.isSelected = currentTool == Tool.CROP
        binding.btnToolErase.isSelected = currentTool == Tool.BRUSH_ERASE
        binding.btnToolRestore.isSelected = currentTool == Tool.BRUSH_RESTORE
    }

    private fun setupTouchHandling() {
        binding.maskPreview.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isErasing = true
                    processTouch(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isErasing) {
                        processTouch(event.x, event.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isErasing = false
                    true
                }
                else -> false
            }
        }
    }

    private fun processTouch(touchX: Float, touchY: Float) {
        if (currentTool == Tool.CROP) return

        val preview = binding.maskPreview
        val bitmap = maskBitmap ?: return

        val scaleX = bitmap.width.toFloat() / preview.width
        val scaleY = bitmap.height.toFloat() / preview.height

        val bitmapX = (touchX * scaleX).toInt().coerceIn(0, bitmap.width - 1)
        val bitmapY = (touchY * scaleY).toInt().coerceIn(0, bitmap.height - 1)

        val brushRadius = (brushSize * min(scaleX, scaleY)).toInt()

        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = if (currentTool == Tool.BRUSH_ERASE) Color.TRANSPARENT else Color.WHITE
            xfermode = if (currentTool == Tool.BRUSH_ERASE) {
                PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                null
            }
            style = Paint.Style.FILL
        }

        canvas.drawCircle(bitmapX.toFloat(), bitmapY.toFloat(), brushRadius.toFloat(), paint)

        if (abs(touchX - lastTouchX) > 1 || abs(touchY - lastTouchY) > 1) {
            val steps = maxOf(abs(touchX - lastTouchX), abs(touchY - lastTouchY)).toInt()
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val ix = lastTouchX + (touchX - lastTouchX) * t
                val iy = lastTouchY + (touchY - lastTouchY) * t
                val bx = (ix * scaleX).toInt().coerceIn(0, bitmap.width - 1)
                val by = (iy * scaleY).toInt().coerceIn(0, bitmap.height - 1)
                canvas.drawCircle(bx.toFloat(), by.toFloat(), brushRadius.toFloat(), paint)
            }
        }

        lastTouchX = touchX
        lastTouchY = touchY

        showCombinedPreview()
    }

    private fun applyCropAndDisplay() {
        val original = originalBitmap ?: return
        val mask = maskBitmap ?: return

        val croppedMask = Bitmap.createBitmap(mask, 0, 0, mask.width, (mask.height * cropPosition).toInt().coerceAtLeast(1))
        val resizedMask = Bitmap.createScaledBitmap(croppedMask, original.width, original.height, true)

        maskBitmap?.recycle()
        maskBitmap = resizedMask

        showCombinedPreview()
    }

    private fun showCombinedPreview() {
        val original = originalBitmap ?: return
        val mask = maskBitmap ?: return

        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(original, 0f, 0f, null)

        val maskScaled = Bitmap.createScaledBitmap(mask, original.width, original.height, true)
        val maskPaint = Paint().apply {
            alpha = 180
        }

        val maskLayer = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskLayer)
        maskCanvas.drawColor(Color.parseColor("#FF4081"))
        maskCanvas.drawBitmap(maskScaled, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })

        canvas.drawBitmap(maskLayer, 0f, 0f, maskPaint)

        displayBitmap?.recycle()
        displayBitmap = result

        binding.maskPreview.setImageBitmap(result)
    }

    private fun saveEditedMask() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE

                val mask = maskBitmap ?: return@launch
                val original = originalBitmap ?: return@launch

                val finalMask = Bitmap.createScaledBitmap(mask, original.width, original.height, true)

                val outputBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outputBitmap)
                canvas.drawBitmap(original, 0f, 0f, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawBitmap(finalMask, 0f, 0f, maskPaint)

                val savedUri = withContext(Dispatchers.IO) {
                    saveBitmapToGallery(outputBitmap, "depth_mask_edited_${System.currentTimeMillis()}")
                }

                binding.progressBar.visibility = View.GONE

                if (savedUri != null) {
                    Toast.makeText(this@MaskEditorActivity, "Mask saved!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@MaskEditorActivity, "Save failed", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MaskEditorActivity, "Save failed", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, filename: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DepthMask")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
        return uri
    }

    override fun onDestroy() {
        super.onDestroy()
        displayBitmap?.recycle()
    }
}