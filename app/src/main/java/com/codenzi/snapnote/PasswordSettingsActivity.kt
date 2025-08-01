package com.codenzi.snapnote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.auth.api.signin.GoogleSignIn // HATA İÇİN EKLENDİ
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.codenzi.snapnote.databinding.ActivityPasswordSettingsBinding
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class PasswordSettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var noteDao: NoteDao
    private lateinit var binding: ActivityPasswordSettingsBinding
    private val gson = Gson()

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressTitle: TextView? = null
    private var progressPercentage: TextView? = null

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleSignInResult(result.data)
        } else {
            Toast.makeText(this, "Google ile oturum açma iptal edildi. Parola değişikliği yedeklenemedi.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPasswordSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPasswordSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.password_settings_title)

        updateUI()

        binding.btnSavePassword.setOnClickListener { savePassword() }
        binding.btnDisablePassword.setOnClickListener { showDisablePasswordConfirmationDialog() }
        binding.btnSecurityInfo.setOnClickListener { showSecurityInfoDialog() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateUI() {
        if (PasswordManager.isPasswordSet()) {
            binding.tilCurrentPassword.visibility = View.VISIBLE
            binding.btnDisablePassword.visibility = View.VISIBLE
            binding.etSecurityQuestion.setText(PasswordManager.getSecurityQuestion())
            binding.etSecurityAnswer.hint = getString(R.string.enter_new_answer_to_change)
        } else {
            binding.tilCurrentPassword.visibility = View.GONE
            binding.btnDisablePassword.visibility = View.GONE
        }
    }

    private fun savePassword() {
        val currentPassword = binding.etCurrentPassword.text.toString()
        val newPassword = binding.etNewPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()
        val securityQuestion = binding.etSecurityQuestion.text.toString()
        val securityAnswer = binding.etSecurityAnswer.text.toString()

        if (PasswordManager.isPasswordSet() && !PasswordManager.checkPassword(currentPassword)) {
            binding.tilCurrentPassword.error = getString(R.string.current_password_incorrect_error)
            return
        }
        binding.tilCurrentPassword.error = null

        if (newPassword.length < 4) {
            binding.tilNewPassword.error = getString(R.string.password_too_short_error)
            return
        }
        binding.tilNewPassword.error = null

        if (newPassword != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.password_mismatch_error)
            return
        }
        binding.tilConfirmPassword.error = null

        if (securityQuestion.isBlank() || securityAnswer.isBlank()) {
            Toast.makeText(this, getString(R.string.security_question_needed_error), Toast.LENGTH_LONG).show()
            return
        }

        PasswordManager.setPasswordAndSecurityQuestion(newPassword, securityQuestion, securityAnswer)
        Toast.makeText(this, getString(R.string.password_set_success), Toast.LENGTH_SHORT).show()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Düzeltme: `auto_backup_on_pass_change` anahtarı preferences.xml'de olmadığı için kontrol kaldırıldı, direkt sor.
        showBackupConfirmationDialog()
    }

    private fun showBackupConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_password_change_title))
            .setMessage(getString(R.string.backup_password_change_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                triggerAutomaticBackup()
            }
            .setNegativeButton(getString(R.string.dialog_no)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showDisablePasswordConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disable_password_button))
            .setMessage(getString(R.string.password_disable_confirmation_message))
            .setPositiveButton(getString(R.string.disable_button)) { _, _ ->
                disablePassword()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun disablePassword() {
        val currentPassword = binding.etCurrentPassword.text.toString()
        if (!PasswordManager.checkPassword(currentPassword)) {
            binding.tilCurrentPassword.error = getString(R.string.current_password_incorrect_error)
            return
        }
        // Düzeltme: `clearPassword` yerine `disablePassword` kullanılıyor.
        PasswordManager.disablePassword()
        Toast.makeText(this, getString(R.string.password_disabled_success), Toast.LENGTH_SHORT).show()

        // Şifre kaldırıldıktan sonra da yedekleme sorusu gösteriliyor.
        showBackupConfirmationDialog()
    }

    private fun showSecurityInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.security_question_and_answer))
            .setMessage(getString(R.string.password_security_explanation))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }

    private fun triggerAutomaticBackup() {
        lifecycleScope.launch {
            val signInClient = Identity.getSignInClient(this@PasswordSettingsActivity)
            val request = GetSignInIntentRequest.builder()
                .setServerClientId(getString(R.string.your_web_client_id))
                .build()
            try {
                val result = signInClient.getSignInIntent(request).await()
                googleSignInLauncher.launch(IntentSenderRequest.Builder(result).build())
            } catch (e: Exception) {
                Log.e("PasswordSettings", "triggerAutomaticBackup failed", e)
                Toast.makeText(this@PasswordSettingsActivity, "Google ile oturum açılamadı. Parola değişikliği yedeklenemedi.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        lifecycleScope.launch {
            try {
                val credential = Identity.getSignInClient(this@PasswordSettingsActivity).getSignInCredentialFromIntent(data)

                // Google'dan gelen kimlik bilgisini doğrudan kullanalım
                val googleCredential = GoogleAccountCredential.usingOAuth2(
                    this@PasswordSettingsActivity,
                    setOf("https://www.googleapis.com/auth/drive.appdata")
                ).apply {
                    selectedAccount = credential.googleIdToken?.let { com.google.android.gms.auth.api.signin.GoogleSignInAccount.createDefault() }?.account // Bu satırda hata olabilir. credential.account kullanılmalı.
                }
                // Düzeltme: credential'dan gelen account bilgisini doğrudan kullanalım.
                val signInAccount = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                googleCredential.selectedAccount = signInAccount.account

                val googleDriveManager = GoogleDriveManager(googleCredential)

                // Yedekleme işlemini başlat
                val notesToBackup = noteDao.getAllNotes().first()
                proceedWithFullBackup(googleDriveManager, notesToBackup)

            } catch (e: ApiException) {
                Log.w("PasswordSettings", "handleSignInResult:failed code=" + e.statusCode, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PasswordSettingsActivity, "Google ile oturum açılamadı. Parola değişikliği yedeklenemedi.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private suspend fun proceedWithFullBackup(googleDriveManager: GoogleDriveManager, notesToBackup: List<Note>) {
        withContext(Dispatchers.Main) {
            showProgressDialog(R.string.backup_in_progress)
            progressPercentage?.visibility = View.VISIBLE
            progressBar?.isIndeterminate = false
        }

        val tempDir = File(cacheDir, "backup_temp").apply { mkdirs() }

        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
            val appSettings = AppSettings(
                themeSelection = sharedPrefs.getString("theme_selection", "system_default"),
                colorSelection = sharedPrefs.getString("color_selection", "rose"),
                widgetBackgroundSelection = sharedPrefs.getString("widget_background_selection", "widget_background")
            )

            val passwordHash = PasswordManager.getPasswordHash()
            val salt = PasswordManager.getSalt()
            val securityQuestion = PasswordManager.getSecurityQuestion()
            val securityAnswerHash = PasswordManager.getSecurityAnswerHash()
            val securitySalt = PasswordManager.getSecuritySalt()

            val notesForBackup = mutableListOf<Note>()
            val totalSteps = notesToBackup.size + 1

            for ((index, note) in notesToBackup.withIndex()) {
                val content = gson.fromJson(note.content, NoteContent::class.java)
                var imageDriveId: String? = null
                content.imagePath?.let { path ->
                    try {
                        val imageUri = Uri.parse(path)
                        contentResolver.openInputStream(imageUri)?.use { inputStream ->
                            val tempFile = File(tempDir, "temp_image_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(tempFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            when (val result = googleDriveManager.uploadMediaFile(tempFile, "image/jpeg")) {
                                is DriveResult.Success -> imageDriveId = result.data
                                is DriveResult.Error -> Log.e("PasswordSettingsBackup", "Görsel yüklenemedi: ${result.exception.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PasswordSettingsBackup", "Görsel işlenirken hata oluştu: $path", e)
                    }
                }

                var audioDriveId: String? = null
                content.audioFilePath?.let { path ->
                    val audioFile = File(path)
                    if (audioFile.exists()) {
                        when (val result = googleDriveManager.uploadMediaFile(audioFile, "audio/mp4")) {
                            is DriveResult.Success -> audioDriveId = result.data
                            is DriveResult.Error -> Log.e("PasswordSettingsBackup", "Ses dosyası yüklenemedi: ${result.exception.message}")
                        }
                    }
                }
                val newContent = content.copy(imagePath = imageDriveId, audioFilePath = audioDriveId)
                notesForBackup.add(note.copy(content = gson.toJson(newContent)))

                val progress = ((index + 1) * 100) / totalSteps
                withContext(Dispatchers.Main) {
                    updateProgress(progress)
                }
            }

            val backupData = BackupData(
                settings = appSettings,
                notes = notesForBackup,
                passwordHash = passwordHash,
                salt = salt,
                securityQuestion = securityQuestion,
                securityAnswerHash = securityAnswerHash,
                securitySalt = securitySalt
            )
            val backupJson = gson.toJson(backupData)

            when (val result = googleDriveManager.uploadJsonBackup("snapnote_backup.json", backupJson)) {
                is DriveResult.Success -> {
                    withContext(Dispatchers.Main) {
                        updateProgress(100)
                        dismissProgressDialog()
                        Toast.makeText(this@PasswordSettingsActivity, getString(R.string.password_change_backup_success), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                is DriveResult.Error -> {
                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                        Toast.makeText(this@PasswordSettingsActivity, getString(R.string.password_change_backup_fail), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@PasswordSettingsActivity, "Yedekleme başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun showProgressDialog(titleResId: Int) {
        val builder = AlertDialog.Builder(this)
        val inflater = this.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_progress, null)

        progressBar = dialogView.findViewById(R.id.progress_bar)
        progressTitle = dialogView.findViewById(R.id.tv_progress_title)
        progressPercentage = dialogView.findViewById(R.id.tv_progress_percentage)

        progressTitle?.text = getString(titleResId)
        progressBar?.isIndeterminate = true
        progressPercentage?.visibility = View.GONE

        builder.setView(dialogView)
        builder.setCancelable(false)
        progressDialog = builder.create()
        progressDialog?.show()
    }

    private fun updateProgress(progress: Int) {
        progressBar?.progress = progress
        progressPercentage?.text = getString(R.string.progress_percentage_format, progress)
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }
}