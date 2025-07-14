package com.codenzi.snapnote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.codenzi.snapnote.databinding.ActivityPasswordSettingsBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
        ActivityResultContracts.StartActivityForResult()
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
        } else {
            binding.tilCurrentPassword.visibility = View.GONE
            binding.btnDisablePassword.visibility = View.GONE
        }
    }

    private fun savePassword() {
        val currentPassword = binding.etCurrentPassword.text.toString()
        val newPassword = binding.etNewPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        if (PasswordManager.isPasswordSet() && !PasswordManager.checkPassword(currentPassword)) {
            Toast.makeText(this, R.string.current_password_incorrect_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword.isBlank() || confirmPassword.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_password_fields_cannot_be_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword.length < 4) {
            Toast.makeText(this, R.string.password_too_short_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword != confirmPassword) {
            Toast.makeText(this, R.string.password_mismatch_error, Toast.LENGTH_SHORT).show()
            return
        }

        PasswordManager.setPassword(newPassword)
        Toast.makeText(this, "Parola ayarlandı. Otomatik yedekleme başlatılıyor...", Toast.LENGTH_SHORT).show()
        triggerAutomaticBackup()
    }

    private fun showDisablePasswordConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.password_disable_confirmation_title)
            .setMessage("Şifreyi devre dışı bırakmak istediğinizden emin misiniz? Bu işlem, Google Drive'daki yedeğinizi de güncelleyecektir.")
            .setPositiveButton(R.string.dialog_yes) { _, _ -> disablePassword() }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun disablePassword() {
        val currentPassword = binding.etCurrentPassword.text.toString()
        if (!PasswordManager.checkPassword(currentPassword)) {
            Toast.makeText(this, R.string.current_password_incorrect_error, Toast.LENGTH_SHORT).show()
            return
        }

        PasswordManager.disablePassword()
        Toast.makeText(this, "Parola kaldırıldı. Otomatik yedekleme başlatılıyor...", Toast.LENGTH_SHORT).show()
        triggerAutomaticBackup()
    }

    private fun triggerAutomaticBackup() {
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")

        if (lastSignedInAccount != null && lastSignedInAccount.grantedScopes.contains(driveScope)) {
            performAutomaticBackup(lastSignedInAccount)
        } else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(driveScope)
                .build()
            val googleSignInClient = GoogleSignIn.getClient(this, gso)
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            performAutomaticBackup(task.getResult(ApiException::class.java)!!)
        } catch (e: ApiException) {
            Log.w("PasswordSettings", "signInResult:failed code=" + e.statusCode, e)
            Toast.makeText(this, "Google ile oturum açılamadı. Parola değişikliği yedeklenemedi.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showProgressDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = this.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_progress, null)

        progressBar = dialogView.findViewById(R.id.progress_bar)
        progressTitle = dialogView.findViewById(R.id.tv_progress_title)
        progressPercentage = dialogView.findViewById(R.id.tv_progress_percentage)

        progressTitle?.text = getString(R.string.backup_update_in_progress)

        builder.setView(dialogView)
        builder.setCancelable(false)
        progressDialog = builder.create()
        progressDialog?.show()
    }

    private fun updateProgress(progress: Int) {
        progressBar?.progress = progress
        progressPercentage?.text = "$progress%"
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun performAutomaticBackup(account: GoogleSignInAccount) {
        lifecycleScope.launch(Dispatchers.IO) {
            val credential = GoogleAccountCredential.usingOAuth2(
                this@PasswordSettingsActivity,
                listOf("https://www.googleapis.com/auth/drive.appdata")
            ).setSelectedAccount(account.account)
            val googleDriveManager = GoogleDriveManager(credential)

            try {
                val notesToBackup = noteDao.getAllNotes().first()
                proceedWithFullBackup(googleDriveManager, notesToBackup)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PasswordSettingsActivity, "Yedekleme sırasında hata: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private suspend fun proceedWithFullBackup(googleDriveManager: GoogleDriveManager, notesToBackup: List<Note>) {
        withContext(Dispatchers.Main) {
            showProgressDialog()
        }

        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
            val appSettings = AppSettings(
                themeSelection = sharedPrefs.getString("theme_selection", "system_default"),
                colorSelection = sharedPrefs.getString("color_selection", "bordo"),
                widgetBackgroundSelection = sharedPrefs.getString("widget_background_selection", "widget_background")
            )

            val passwordHash = PasswordManager.getPasswordHash()
            val salt = PasswordManager.getSalt()

            val notesForBackup = mutableListOf<Note>()
            val totalSteps = notesToBackup.size + 1

            for ((index, note) in notesToBackup.withIndex()) {
                val content = gson.fromJson(note.content, NoteContent::class.java)
                var imageDriveId: String? = null
                content.imagePath?.let { path ->
                    try { File(path.toUri().path!!) } catch (e: Exception) { null }?.let { imageFile ->
                        if (imageFile.exists()) {
                            imageDriveId = googleDriveManager.uploadMediaFile(imageFile, "image/jpeg")
                        }
                    }
                }
                var audioDriveId: String? = null
                content.audioFilePath?.let { path ->
                    val audioFile = File(path)
                    if (audioFile.exists()) {
                        audioDriveId = googleDriveManager.uploadMediaFile(audioFile, "audio/mp4")
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
                salt = salt
            )
            val backupJson = gson.toJson(backupData)

            val success = googleDriveManager.uploadJsonBackup("snapnote_backup.json", backupJson)

            withContext(Dispatchers.Main) {
                updateProgress(100)
                dismissProgressDialog()
                if (success) {
                    Toast.makeText(this@PasswordSettingsActivity, "Parola değişikliği Google Drive yedeğine başarıyla yansıtıldı.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@PasswordSettingsActivity, "Parola değiştirildi ancak Drive yedeği güncellenemedi.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@PasswordSettingsActivity, "Yedekleme başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showSecurityInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.security_info_title))
            .setMessage(R.string.password_security_explanation)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }
}