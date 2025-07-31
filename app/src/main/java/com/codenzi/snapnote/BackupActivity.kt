package com.codenzi.snapnote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.codenzi.snapnote.databinding.ActivityBackupBinding
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class BackupActivity : AppCompatActivity() {

    @Inject
    lateinit var noteDao: NoteDao
    private val gson = Gson()

    private lateinit var binding: ActivityBackupBinding

    private var requestedAction: Action? = null
    private enum class Action { BACKUP, RESTORE, DELETE, EXPORT_LOCAL, IMPORT_LOCAL }

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
            Toast.makeText(this, getString(R.string.google_sign_in_cancelled), Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var localBackupCreator: ActivityResultLauncher<String>
    private lateinit var localBackupSelector: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarBackup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.data_management_title)

        localBackupCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    exportToDevice(it)
                }
            }
        }

        localBackupSelector = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    importFromDevice(it)
                }
            }
        }

        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupListeners() {
        binding.btnGoogleDriveBackup.setOnClickListener {
            requestedAction = Action.BACKUP
            signInToGoogle()
        }
        binding.btnGoogleDriveRestore.setOnClickListener {
            requestedAction = Action.RESTORE
            signInToGoogle()
        }
        binding.btnExportToPhone.setOnClickListener {
            requestedAction = Action.EXPORT_LOCAL
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            localBackupCreator.launch("SnapNote_Backup_$timeStamp.zip")
        }
        binding.btnImportFromPhone.setOnClickListener {
            requestedAction = Action.IMPORT_LOCAL
            localBackupSelector.launch(arrayOf("application/zip"))
        }
        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountConfirmationDialog()
        }
    }

    private suspend fun exportToDevice(uri: Uri) {
        withContext(Dispatchers.Main) {
            showProgressDialog(R.string.action_export_in_progress)
            progressBar?.isIndeterminate = true
            progressPercentage?.visibility = View.GONE
        }

        try {
            val notesToBackup = noteDao.getAllNotes().first()
            if (notesToBackup.isEmpty()) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(this@BackupActivity, getString(R.string.no_notes_to_backup), Toast.LENGTH_SHORT).show()
                }
                return
            }

            val tempDir = File(cacheDir, "backup_temp").apply { mkdirs() }
            val filesToZip = mutableListOf<File>()

            val notesForJson = notesToBackup.map { note ->
                val content = gson.fromJson(note.content, NoteContent::class.java)
                var newImagePath: String? = null
                content.imagePath?.let { path ->
                    try {
                        val imageUri = Uri.parse(path)
                        val fileName = imageUri.lastPathSegment ?: "image_${System.currentTimeMillis()}.jpg"
                        val destFile = File(tempDir, fileName)

                        contentResolver.openInputStream(imageUri)?.use { inputStream ->
                            FileOutputStream(destFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        if (destFile.exists()) {
                            filesToZip.add(destFile)
                            newImagePath = destFile.name
                        }
                    } catch (e: Exception) {
                        Log.e("BackupActivity", "Yerel dışa aktarma için görsel işlenirken hata: $path", e)
                    }
                }
                var newAudioPath: String? = null
                content.audioFilePath?.let { path ->
                    val sourceFile = File(path)
                    if (sourceFile.exists()) {
                        val destFile = File(tempDir, sourceFile.name)
                        sourceFile.copyTo(destFile, overwrite = true)
                        filesToZip.add(destFile)
                        newAudioPath = sourceFile.name
                    }
                }
                val newContent = content.copy(imagePath = newImagePath, audioFilePath = newAudioPath)
                note.copy(content = gson.toJson(newContent))
            }

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

            val backupData = BackupData(
                settings = appSettings,
                notes = notesForJson,
                passwordHash = passwordHash,
                salt = salt,
                securityQuestion = securityQuestion,
                securityAnswerHash = securityAnswerHash,
                securitySalt = securitySalt
            )
            val jsonFile = File(tempDir, "backup.json")
            jsonFile.writeText(gson.toJson(backupData))
            filesToZip.add(jsonFile)

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    filesToZip.forEach { file ->
                        FileInputStream(file).use { fileIn ->
                            BufferedInputStream(fileIn).use { bufferedIn ->
                                val entry = ZipEntry(file.name)
                                zipOut.putNextEntry(entry)
                                bufferedIn.copyTo(zipOut, 1024)
                                zipOut.closeEntry()
                            }
                        }
                    }
                }
            }

            tempDir.deleteRecursively()

            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@BackupActivity, getString(R.string.export_successful), Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@BackupActivity, getString(R.string.export_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun importFromDevice(uri: Uri) {
        val tempDir = File(cacheDir, "import_temp").apply { mkdirs() }

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        val outputFile = File(tempDir, entry.name)
                        FileOutputStream(outputFile).use { fos ->
                            zipIn.copyTo(fos)
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }

            val jsonFile = File(tempDir, "backup.json")
            if (!jsonFile.exists()) {
                throw IOException("backup.json not found in ZIP file.")
            }
            val backupData: BackupData = gson.fromJson(jsonFile.readText(), object : TypeToken<BackupData>() {}.type)

            withContext(Dispatchers.Main) {
                if (backupData.passwordHash != null) {
                    showRestoreAuthChoiceDialog(null, backupData, tempDir)
                } else {
                    showLocalRestoreConfirmationDialog(backupData, tempDir)
                }
            }

        } catch (e: Exception) {
            tempDir.deleteRecursively()
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@BackupActivity, getString(R.string.import_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLocalRestoreConfirmationDialog(backupData: BackupData, tempDir: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_dialog_title))
            .setMessage(getString(R.string.import_dialog_message))
            .setPositiveButton(getString(R.string.restore_confirm)) { _, _ ->
                lifecycleScope.launch {
                    proceedWithLocalRestore(backupData, tempDir)
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) {_,_ ->
                tempDir.deleteRecursively()
            }
            .show()
    }

    private fun showPasswordPromptForLocalRestore(backupData: BackupData, tempDir: File) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.enter_current_password_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.password_required_title))
            .setMessage(getString(R.string.import_password_protected_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                val enteredPassword = editText.text.toString()
                if (PasswordManager.checkPassword(enteredPassword, backupData.salt!!, backupData.passwordHash!!)) {
                    lifecycleScope.launch {
                        proceedWithLocalRestore(backupData, tempDir)
                    }
                } else {
                    Toast.makeText(this, getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show()
                    tempDir.deleteRecursively()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) { _, _ ->
                tempDir.deleteRecursively()
            }
            .show()
    }

    private suspend fun proceedWithLocalRestore(backupData: BackupData, tempDir: File) {
        withContext(Dispatchers.Main) {
            showProgressDialog(R.string.action_import_in_progress)
            progressBar?.isIndeterminate = true
            progressPercentage?.visibility = View.GONE
        }

        try {
            noteDao.deleteAllNotes()

            val restoredNotes = backupData.notes.map { note ->
                val content = gson.fromJson(note.content, NoteContent::class.java)
                var newImagePath: String? = null
                content.imagePath?.let { fileName ->
                    val sourceFile = File(tempDir, fileName)
                    if (sourceFile.exists()) {
                        val permanentDir = getExternalFilesDir("Images")!!
                        val permanentFile = File(permanentDir, fileName)
                        sourceFile.copyTo(permanentFile, overwrite = true)
                        newImagePath = permanentFile.absolutePath
                    }
                }
                var newAudioPath: String? = null
                content.audioFilePath?.let { fileName ->
                    val sourceFile = File(tempDir, fileName)
                    if(sourceFile.exists()){
                        val permanentDir = getExternalFilesDir("AudioNotes")!!
                        val permanentFile = File(permanentDir, fileName)
                        sourceFile.copyTo(permanentFile, overwrite = true)
                        newAudioPath = permanentFile.absolutePath
                    }
                }
                val newContent = content.copy(imagePath = newImagePath, audioFilePath = newAudioPath)
                note.copy(content = gson.toJson(newContent))
            }

            noteDao.insertAll(restoredNotes)

            val prefsEditor = PreferenceManager.getDefaultSharedPreferences(this).edit()
            prefsEditor.putString("theme_selection", backupData.settings.themeSelection)
            prefsEditor.putString("color_selection", backupData.settings.colorSelection ?: "rose")
            prefsEditor.putString("widget_background_selection", backupData.settings.widgetBackgroundSelection)
            prefsEditor.apply()

            PasswordManager.resetForRestore(this)
            PasswordManager.restoreAllSecurityCredentials(backupData)

            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@BackupActivity, getString(R.string.import_successful), Toast.LENGTH_LONG).show()
                val intent = Intent(this@BackupActivity, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }

        } catch(e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                Toast.makeText(this@BackupActivity, getString(R.string.import_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun showDeleteAccountConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_confirmation_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                showFinalDeleteConfirmationDialog()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun showFinalDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.final_delete_confirmation_title))
            .setMessage(getString(R.string.final_delete_confirmation_message))
            .setPositiveButton(getString(R.string.final_delete_confirm_button)) { _, _ ->
                requestedAction = Action.DELETE
                signInToGoogle()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun signInToGoogle() {
        lifecycleScope.launch {
            val signInClient = Identity.getSignInClient(this@BackupActivity)
            val request = GetSignInIntentRequest.builder()
                .setServerClientId(getString(R.string.your_web_client_id))
                .build()
            try {
                val result = signInClient.getSignInIntent(request).await()
                googleSignInLauncher.launch(IntentSenderRequest.Builder(result).build())
            } catch (e: Exception) {
                Log.e("BackupActivity", "Sign-in failed", e)
                Toast.makeText(this@BackupActivity, getString(R.string.sign_in_error_try_again), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        lifecycleScope.launch {
            try {
                val credential = Identity.getSignInClient(this@BackupActivity).getSignInCredentialFromIntent(data)
                val googleCredential = GoogleAccountCredential.usingOAuth2(
                    this@BackupActivity,
                    listOf("https://www.googleapis.com/auth/drive.appdata")
                )
                googleCredential.selectedAccountName = credential.id
                val googleDriveManager = GoogleDriveManager(googleCredential)

                when (requestedAction) {
                    Action.BACKUP -> backupNotes(googleDriveManager)
                    Action.RESTORE -> restoreNotes(googleDriveManager)
                    Action.DELETE -> performAccountDeletion(googleDriveManager)
                    else -> {}
                }
            } catch (e: ApiException) {
                Log.w("BackupActivity", "signInResult:failed code=" + e.statusCode, e)
                Toast.makeText(this@BackupActivity, getString(R.string.sign_in_error_try_again), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("BackupActivity", "handleSignInResult'ta genel hata", e)
                Toast.makeText(this@BackupActivity, getString(R.string.an_unknown_error_occurred), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performAccountDeletion(googleDriveManager: GoogleDriveManager) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    showProgressDialog(R.string.delete_in_progress)
                }

                when (val deleteResult = googleDriveManager.deleteFile("snapnote_backup.json")) {
                    is DriveResult.Success -> {
                        Identity.getSignInClient(this@BackupActivity).signOut().await()
                        withContext(Dispatchers.Main) {
                            if (!DataWipeManager.wipeAllData(this@BackupActivity)) {
                                dismissProgressDialog()
                                Toast.makeText(this@BackupActivity, "Hesap silinemedi.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    is DriveResult.Error -> {
                        throw deleteResult.exception
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(this@BackupActivity, getString(R.string.account_deletion_failed_with_error, e.message), Toast.LENGTH_LONG).show()
                }
                Log.e("BackupActivity", "Account deletion failed", e)
            }
        }
    }

    private fun backupNotes(googleDriveManager: GoogleDriveManager) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localNotes = noteDao.getAllNotes().first()

                if (localNotes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BackupActivity, getString(R.string.no_notes_to_backup), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                when (val result = googleDriveManager.getBackupFiles()) {
                    is DriveResult.Success -> {
                        val existingBackup = result.data.firstOrNull()
                        withContext(Dispatchers.Main) {
                            if (existingBackup != null) {
                                AlertDialog.Builder(this@BackupActivity)
                                    .setTitle(getString(R.string.existing_backup_found_title))
                                    .setMessage(getString(R.string.existing_backup_found_message))
                                    .setPositiveButton(getString(R.string.overwrite_button)) { _, _ ->
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            proceedWithBackup(googleDriveManager, localNotes)
                                        }
                                    }
                                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                                    .show()
                            } else {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    proceedWithBackup(googleDriveManager, localNotes)
                                }
                            }
                        }
                    }
                    is DriveResult.Error -> {
                        showError("Error during backup check", result.exception)
                    }
                }
            } catch (e: Exception) {
                showError("Error during backup check", e)
            }
        }
    }

    private suspend fun proceedWithBackup(googleDriveManager: GoogleDriveManager, notesToBackup: List<Note>) {
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
                                is DriveResult.Error -> Log.e("BackupActivity", "Görsel yüklenemedi: ${result.exception.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BackupActivity", "Görsel işlenirken hata oluştu: $path", e)
                    }
                }

                var audioDriveId: String? = null
                content.audioFilePath?.let { path ->
                    val audioFile = File(path)
                    if (audioFile.exists()) {
                        when (val result = googleDriveManager.uploadMediaFile(audioFile, "audio/mp4")) {
                            is DriveResult.Success -> audioDriveId = result.data
                            is DriveResult.Error -> Log.e("BackupActivity", "Ses dosyası yüklenemedi: ${result.exception.message}")
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
                        Toast.makeText(this@BackupActivity, getString(R.string.backup_successful), Toast.LENGTH_SHORT).show()
                    }
                }
                is DriveResult.Error -> {
                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                        Toast.makeText(this@BackupActivity, getString(R.string.an_error_occurred_during_backup_simple), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
            }
            showError("Backup failed", e)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun restoreNotes(googleDriveManager: GoogleDriveManager) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    showProgressDialog(R.string.searching_for_backups)
                }

                when (val filesResult = googleDriveManager.getBackupFiles()) {
                    is DriveResult.Success -> {
                        val backupFile = filesResult.data.firstOrNull()
                        if (backupFile == null) {
                            withContext(Dispatchers.Main) {
                                dismissProgressDialog()
                                Toast.makeText(this@BackupActivity, getString(R.string.backup_not_found), Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }

                        when (val contentResult = googleDriveManager.downloadJsonBackup(backupFile.id)) {
                            is DriveResult.Success -> {
                                val jsonContent = contentResult.data
                                if (jsonContent.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        dismissProgressDialog()
                                        Toast.makeText(this@BackupActivity, getString(R.string.backup_file_empty_or_corrupt), Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                val type = object : TypeToken<BackupData>() {}.type
                                val backupData: BackupData = gson.fromJson(jsonContent, type)
                                if (backupData.notes.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        dismissProgressDialog()
                                        Toast.makeText(this@BackupActivity, "Yedek dosyası bulundu ancak içerisinde geri yüklenecek not yok.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                withContext(Dispatchers.Main) {
                                    dismissProgressDialog()
                                    if (backupData.passwordHash != null) {
                                        showRestoreAuthChoiceDialog(googleDriveManager, backupData, null)
                                    } else {
                                        showDriveRestoreConfirmationDialog(googleDriveManager, backupData)
                                    }
                                }
                            }
                            is DriveResult.Error -> throw contentResult.exception
                        }
                    }
                    is DriveResult.Error -> throw filesResult.exception
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                }
                showError(getString(R.string.restore_failed), e)
            }
        }
    }

    private fun showRestoreAuthChoiceDialog(googleDriveManager: GoogleDriveManager?, backupData: BackupData, tempDir: File?) {
        val options = arrayOf(
            getString(R.string.restore_auth_choice_know_password),
            getString(R.string.restore_auth_choice_forgot_password)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_auth_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Şifremi Biliyorum
                        if (googleDriveManager != null) {
                            showPasswordPromptForRestore(googleDriveManager, backupData)
                        } else if (tempDir != null) {
                            showPasswordPromptForLocalRestore(backupData, tempDir)
                        }
                    }
                    1 -> { // Şifremi Unuttum
                        if (googleDriveManager != null) {
                            showSecurityQuestionPromptForRestore(googleDriveManager, backupData)
                        } else if (tempDir != null) {
                            showSecurityQuestionPromptForLocalRestore(backupData, tempDir)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                tempDir?.deleteRecursively()
            }
            .show()
    }

    private fun showSecurityQuestionPromptForRestore(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
        val securityQuestion = backupData.securityQuestion
        if (securityQuestion.isNullOrBlank()) {
            Toast.makeText(this, "Bu yedekte güvenlik sorusu bulunmuyor.", Toast.LENGTH_LONG).show()
            return
        }

        val answerInput = EditText(this).apply {
            hint = getString(R.string.hint_security_answer)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            val questionText = TextView(context).apply {
                text = securityQuestion
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                setPadding(0, 0, 0, padding)
            }
            addView(questionText)
            addView(answerInput)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.security_question_title)
            .setView(layout)
            .setPositiveButton(R.string.submit_button) { _, _ ->
                val answer = answerInput.text.toString()
                if (PasswordManager.checkPassword(answer, backupData.securitySalt!!, backupData.securityAnswerHash!!)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        proceedWithRestore(googleDriveManager, backupData)
                    }
                } else {
                    Toast.makeText(this, R.string.incorrect_security_answer, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showSecurityQuestionPromptForLocalRestore(backupData: BackupData, tempDir: File) {
        val securityQuestion = backupData.securityQuestion
        if (securityQuestion.isNullOrBlank()) {
            Toast.makeText(this, "Bu yedekte güvenlik sorusu bulunmuyor.", Toast.LENGTH_LONG).show()
            tempDir.deleteRecursively()
            return
        }

        val answerInput = EditText(this).apply {
            hint = getString(R.string.hint_security_answer)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            val questionText = TextView(context).apply {
                text = securityQuestion
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                setPadding(0, 0, 0, padding)
            }
            addView(questionText)
            addView(answerInput)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.security_question_title)
            .setView(layout)
            .setPositiveButton(R.string.submit_button) { _, _ ->
                val answer = answerInput.text.toString()
                if (PasswordManager.checkPassword(answer, backupData.securitySalt!!, backupData.securityAnswerHash!!)) {
                    lifecycleScope.launch {
                        proceedWithLocalRestore(backupData, tempDir)
                    }
                } else {
                    Toast.makeText(this, R.string.incorrect_security_answer, Toast.LENGTH_SHORT).show()
                    tempDir.deleteRecursively()
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                tempDir.deleteRecursively()
            }
            .show()
    }

    private fun showDriveRestoreConfirmationDialog(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_dialog_title))
            .setMessage(getString(R.string.restore_from_drive_dialog_message))
            .setPositiveButton(getString(R.string.restore_confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    proceedWithRestore(googleDriveManager, backupData)
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showPasswordPromptForRestore(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.enter_current_password_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.password_required_title))
            .setMessage(getString(R.string.backup_password_protected_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                val enteredPassword = editText.text.toString()
                if (backupData.passwordHash != null && backupData.salt != null) {
                    if (PasswordManager.checkPassword(enteredPassword, backupData.salt, backupData.passwordHash)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            proceedWithRestore(googleDriveManager, backupData)
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private suspend fun proceedWithRestore(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
        withContext(Dispatchers.Main) {
            showProgressDialog(R.string.restore_in_progress)
            progressPercentage?.visibility = View.VISIBLE
            progressBar?.isIndeterminate = false
        }

        try {
            val notesFromBackup = backupData.notes
            val restoredNotes = mutableListOf<Note>()
            val tempFiles = mutableListOf<File>()
            val totalSteps = notesFromBackup.size + 1

            var isDownloadSuccessful = true

            notesFromBackup.forEachIndexed { index, note ->
                if (!isDownloadSuccessful) return@forEachIndexed

                val content = gson.fromJson(note.content, NoteContent::class.java)
                var localImagePath: String? = null
                content.imagePath?.let { driveId ->
                    val imageFile = createImageFile()
                    val outputStream = FileOutputStream(imageFile)
                    when(val result = googleDriveManager.downloadMediaFile(driveId, outputStream)) {
                        is DriveResult.Success -> {
                            localImagePath = imageFile.absolutePath
                            tempFiles.add(imageFile)
                        }
                        is DriveResult.Error -> {
                            isDownloadSuccessful = false
                            imageFile.delete()
                        }
                    }
                }
                var localAudioPath: String? = null
                content.audioFilePath?.let { driveId ->
                    val audioFile = createAudioFile()
                    val outputStream = FileOutputStream(audioFile)
                    when(val result = googleDriveManager.downloadMediaFile(driveId, outputStream)) {
                        is DriveResult.Success -> {
                            localAudioPath = audioFile.absolutePath
                            tempFiles.add(audioFile)
                        }
                        is DriveResult.Error -> {
                            isDownloadSuccessful = false
                            audioFile.delete()
                        }
                    }
                }
                val finalContent = content.copy(imagePath = localImagePath, audioFilePath = localAudioPath)
                restoredNotes.add(note.copy(content = gson.toJson(finalContent)))

                val progress = ((index + 1) * 95) / totalSteps
                withContext(Dispatchers.Main) {
                    updateProgress(progress)
                }
            }

            if (isDownloadSuccessful) {
                noteDao.deleteAllNotes()
                noteDao.insertAll(restoredNotes)

                val prefsEditor = PreferenceManager.getDefaultSharedPreferences(this).edit()
                prefsEditor.putString("theme_selection", backupData.settings.themeSelection)
                prefsEditor.putString("color_selection", backupData.settings.colorSelection ?: "rose")
                prefsEditor.putString("widget_background_selection", backupData.settings.widgetBackgroundSelection)
                prefsEditor.apply()

                PasswordManager.resetForRestore(this)
                PasswordManager.restoreAllSecurityCredentials(backupData)

                withContext(Dispatchers.Main) {
                    updateProgress(100)
                    dismissProgressDialog()
                    Toast.makeText(this@BackupActivity, getString(R.string.restore_success), Toast.LENGTH_LONG).show()
                    val intent = Intent(this@BackupActivity, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                }
            } else {
                tempFiles.forEach { it.delete() }
                throw IOException("Medya dosyası indirilemedi, işlem iptal edildi.")
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                showError(getString(R.string.restore_failed_with_error, e.message), e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir("RestoredImages") ?: filesDir
        storageDir.mkdirs()
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    @Throws(IOException::class)
    private fun createAudioFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir("RestoredAudio") ?: filesDir
        storageDir.mkdirs()
        return File.createTempFile("AUD_${timeStamp}_", ".mp3", storageDir)
    }

    private suspend fun showError(message: String, e: Exception) {
        withContext(Dispatchers.Main) {
            val finalMessage = if (e.message != null) {
                getString(R.string.restore_failed_with_error, e.message)
            } else {
                message
            }
            Toast.makeText(this@BackupActivity, finalMessage, Toast.LENGTH_LONG).show()
            Log.e("BackupActivity", message, e)
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