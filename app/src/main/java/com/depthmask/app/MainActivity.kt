package com.depthmask.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.depthmask.app.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.subject.SubjectSegmentation
import com.google.mlkit.vision.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            selectedBitmap = it
            showPreview(it)
            runSegmentation(it)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            openImagePicker()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.let {
            if (it.action == Intent.ACTION_SEND && it.type?.startsWith("image/") == true) {
                val uri = it.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let { loadImage(it) }
            }
        }
    }

    private fun setupUI() {
        binding.btnPickImage.setOnClickListener {
            checkPermissionsAndPick()
        }

        binding.btnTakePhoto.setOnClickListener {
            takePictureLauncher.launch(null)
        }

        binding.btnEditMask.setOnClickListener {
            selectedBitmap?.let { bitmap ->
                maskBitmap?.let { mask ->
                    val intent = Intent(this, MaskEditorActivity::class.java).apply {
                        putExtra("bitmap_uri", saveBitmapTemp(bitmap))
                        putExtra("mask_width", mask.width)
                        putExtra("mask_height", mask.height)
                    }
                    startActivity(intent)
                } ?: Toast.makeText(this, "No mask generated yet", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveMask.setOnClickListener {
            saveMaskAsPng()
        }
    }

    private fun checkPermissionsAndPick() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            openImagePicker()
        }
    }

    private fun openImagePicker() {
        pickImageLauncher.launch("image/*")
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.text = "Loading image..."

                val bitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                }

                selectedBitmap = bitmap
                showPreview(bitmap)
                runSegmentation(bitmap)

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private fun showPreview(bitmap: Bitmap) {
        binding.previewImage.setImageBitmap(bitmap)
        binding.previewImage.visibility = View.VISIBLE
        binding.placeholderView.visibility = View.GONE
    }

    private fun runSegmentation(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.text = "Detecting subject..."
                binding.btnEditMask.isEnabled = false
                binding.btnSaveMask.isEnabled = false

                val image = InputImage.fromBitmap(bitmap, 0)

                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundBitmap()
                    .build()

                val segmenter = SubjectSegmentation.getClient(options)

                val result = withContext(Dispatchers.IO) {
                    segmenter.process(image).await()
                }

                val foregroundBitmap = result.foregroundBitmap

                if (foregroundBitmap != null) {
                    maskBitmap = foregroundBitmap
                    showSegmentationResult(bitmap, foregroundBitmap)
                    binding.statusText.text = "Subject detected! Tap Edit to adjust."
                    binding.btnEditMask.isEnabled = true
                    binding.btnSaveMask.isEnabled = true
                } else {
                    binding.statusText.text = "No subject detected. Try another image."
                }

                binding.progressBar.visibility = View.GONE
                segmenter.close()

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = "Segmentation failed: ${e.message}"
                Toast.makeText(this@MainActivity, "Segmentation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private fun showSegmentationResult(original: Bitmap, foreground: Bitmap) {
        val width = original.width.coerceAtLeast(foreground.width)
        val height = original.height.coerceAtLeast(foreground.height)

        val scaledOriginal = Bitmap.createScaledBitmap(original, width, height, true)
        val scaledForeground = Bitmap.createScaledBitmap(foreground, width, height, true)

        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(combined)
        canvas.drawBitmap(scaledOriginal, 0f, 0f, null)

        val paint = android.graphics.Paint().apply {
            alpha = 128
        }
        canvas.drawBitmap(scaledForeground, 0f, 0f, paint)

        binding.previewImage.setImageBitmap(combined)
    }

    private fun saveMaskAsPng() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.text = "Saving mask..."

                val mask = maskBitmap ?: return@launch
                val bitmap = selectedBitmap ?: return@launch

                val resizedMask = Bitmap.createScaledBitmap(mask, bitmap.width, bitmap.height, true)

                val savedUri = withContext(Dispatchers.IO) {
                    saveBitmapToGallery(resizedMask, "depth_mask_${System.currentTimeMillis()}")
                }

                binding.progressBar.visibility = View.GONE

                if (savedUri != null) {
                    binding.statusText.text = "Mask saved! Ready to import."
                    Toast.makeText(this@MainActivity, "Mask saved to gallery", Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusText.text = "Failed to save mask"
                }

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Save failed", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private fun saveBitmapTemp(bitmap: Bitmap): Uri {
        val file = java.io.File(cacheDir, "temp_image.png")
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return Uri.fromFile(file)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, filename: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            uri
        } else {
            @Suppress("DEPRECATION")
            val path = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val file = java.io.File(path, "$filename.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Uri.fromFile(file)
        }
    }
}