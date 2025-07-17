package com.codenzi.snapnote

import android.content.Intent // HATA İÇİN EKLENEN SATIR
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.codenzi.snapnote.databinding.ActivityForgotPasswordBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val securityQuestion = PasswordManager.getSecurityQuestion()
        if (securityQuestion.isNullOrBlank()) {
            Toast.makeText(this, "Güvenlik sorusu ayarlanmamış.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.tvSecurityQuestion.text = securityQuestion
        binding.btnSubmitAnswer.setOnClickListener {
            checkAnswer()
        }
    }

    private fun checkAnswer() {
        val answer = binding.etSecurityAnswer.text.toString().trim()
        if (answer.isEmpty()) {
            Toast.makeText(this, "Lütfen cevabınızı girin.", Toast.LENGTH_SHORT).show()
            return
        }

        if (PasswordManager.checkSecurityAnswer(answer)) {
            showResetPasswordDialog()
        } else {
            Toast.makeText(this, getString(R.string.incorrect_security_answer), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResetPasswordDialog() {
        val newPasswordLayout = TextInputLayout(this).apply {
            hint = getString(R.string.enter_new_password_hint)
            isPasswordVisibilityToggleEnabled = true
        }
        val newPasswordInput = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        newPasswordLayout.addView(newPasswordInput)

        val confirmPasswordLayout = TextInputLayout(this).apply {
            hint = getString(R.string.confirm_new_password_hint)
            isPasswordVisibilityToggleEnabled = true
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = params
        }
        val confirmPasswordInput = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        confirmPasswordLayout.addView(confirmPasswordInput)


        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(newPasswordLayout)
            addView(confirmPasswordLayout)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_password_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save_password_button)) { _, _ ->
                val newPassword = newPasswordInput.text.toString()
                val confirmPassword = confirmPasswordInput.text.toString()

                if (newPassword.length < 4) {
                    Toast.makeText(this, getString(R.string.password_too_short_error), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newPassword != confirmPassword) {
                    Toast.makeText(this, getString(R.string.password_mismatch_error), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                PasswordManager.resetPassword(newPassword)
                Toast.makeText(this, getString(R.string.password_reset_success), Toast.LENGTH_LONG).show()
                finishAffinity()
                startActivity(Intent(this, PasswordCheckActivity::class.java))

            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }
}