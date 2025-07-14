package com.codenzi.snapnote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.codenzi.snapnote.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Eğer kullanıcı tanıtımı daha önce gördüyse, direkt ana ekrana yönlendir.
        val sharedPrefs = getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean("has_seen_onboarding", false)) {
            navigateToMain()
            return
        }

        binding.btnStart.setOnClickListener {
            // Kullanıcının tanıtımı gördüğünü kaydet.
            sharedPrefs.edit().putBoolean("has_seen_onboarding", true).apply()
            // Ana ekrana yönlendir.
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, PasswordCheckActivity::class.java)
        startActivity(intent)
        finish() // Bu aktiviteyi geri tuşuyla tekrar açılmaması için kapat.
    }
}