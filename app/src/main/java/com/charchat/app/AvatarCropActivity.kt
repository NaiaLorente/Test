package com.charchat.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Lets the user pick exactly what part of a photo becomes a character's avatar before it's saved,
 * instead of always auto-cropping to the center - which is what was cutting characters off.
 */
class AvatarCropActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_avatar_crop)
        findViewById<View>(R.id.crop_root).applySystemBarInsetsAsPadding()

        cropView = findViewById(R.id.crop_view)
        val confirmButton = findViewById<View>(R.id.crop_confirm)
        confirmButton.isEnabled = false

        findViewById<View>(R.id.crop_cancel).setOnClickListener { finish() }
        confirmButton.setOnClickListener { confirmCrop() }

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = decodeSourceBitmap()
            withContext(Dispatchers.Main) {
                if (bitmap == null) {
                    Toast.makeText(this@AvatarCropActivity, "Couldn't load the image", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    cropView.setImageBitmap(bitmap)
                    confirmButton.isEnabled = true
                }
            }
        }
    }

    private fun decodeSourceBitmap(): Bitmap? = runCatching {
        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.let { Uri.parse(it) }
        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)

        fun open(): InputStream? = when {
            sourceUri != null -> contentResolver.openInputStream(sourceUri)
            sourcePath != null -> File(sourcePath).inputStream()
            else -> null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxDimension / sampleSize > MAX_SOURCE_DIMENSION) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun confirmCrop() {
        val cropped = cropView.croppedBitmap() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            runCatching {
                FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            }
            withContext(Dispatchers.Main) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.path))
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_PATH = "source_path"
        const val EXTRA_RESULT_PATH = "result_path"
        private const val MAX_SOURCE_DIMENSION = 2048
    }
}
