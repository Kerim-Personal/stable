package com.codenzi.snapnote

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.codenzi.snapnote.databinding.ActivityPhotoViewBinding
import java.io.File

class PhotoViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUriString = intent.getStringExtra("IMAGE_URI")
        if (imageUriString != null) {
            val dataToLoad: Any = if (imageUriString.startsWith("content://")) {
                Uri.parse(imageUriString)
            } else {
                File(imageUriString)
            }
            binding.ivFullscreenPhoto.load(dataToLoad) {
                crossfade(true)
                error(R.drawable.ic_image_24)
            }
        }

        binding.btnClosePhotoView.setOnClickListener {
            finish()
        }

        binding.ivFullscreenPhoto.setOnClickListener{
            finish()
        }
    }
}