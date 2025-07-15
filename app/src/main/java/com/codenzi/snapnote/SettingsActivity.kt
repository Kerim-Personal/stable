package com.codenzi.snapnote

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
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

// Data sınıfları aynı kalıyor
data class AppSettings(
    val themeSelection: String?,
    val colorSelection: String?,
    val widgetBackgroundSelection: String?
)

data class BackupData(
    val settings: AppSettings,
    val notes: List<Note>,
    val passwordHash: String?,
    val salt: String?
)

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: Toolbar = findViewById(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    @AndroidEntryPoint
    class SettingsFragment : PreferenceFragmentCompat() {

        @Inject
        lateinit var noteDao: NoteDao
        private val gson = Gson()

        private var requestedAction: Action? = null
        enum class Action { BACKUP, RESTORE, DELETE, EXPORT_LOCAL, IMPORT_LOCAL }

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
                Toast.makeText(requireContext(), getString(R.string.google_sign_in_cancelled), Toast.LENGTH_SHORT).show()
            }
        }

        private lateinit var localBackupCreator: ActivityResultLauncher<String>
        private lateinit var localBackupSelector: ActivityResultLauncher<Array<String>>

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

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
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("export_to_phone")?.setOnPreferenceClickListener {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                localBackupCreator.launch("SnapNote_Backup_$timeStamp.zip")
                true
            }

            findPreference<Preference>("import_from_phone")?.setOnPreferenceClickListener {
                localBackupSelector.launch(arrayOf("application/zip"))
                true
            }

            findPreference<ListPreference>("theme_selection")?.setOnPreferenceChangeListener { _, newValue ->
                val mode = when (newValue as String) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                true
            }

            findPreference<ListPreference>("color_selection")?.setOnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

            findPreference<ListPreference>("widget_background_selection")?.setOnPreferenceChangeListener { _, _ ->
                activity?.window?.decorView?.post {
                    updateAllWidgets()
                }
                true
            }

            findPreference<Preference>("add_widget_shortcut")?.setOnPreferenceClickListener {
                showAddWidgetDialog()
                true
            }

            findPreference<Preference>("google_drive_backup")?.setOnPreferenceClickListener {
                requestedAction = Action.BACKUP
                signInToGoogle()
                true
            }

            findPreference<Preference>("google_drive_restore")?.setOnPreferenceClickListener {
                requestedAction = Action.RESTORE
                signInToGoogle()
                true
            }

            findPreference<Preference>("delete_account")?.setOnPreferenceClickListener {
                showDeleteAccountConfirmationDialog()
                true
            }

            findPreference<Preference>("password_settings")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PasswordSettingsActivity::class.java))
                true
            }

            findPreference<Preference>("trash_settings")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), TrashActivity::class.java))
                true
            }

            findPreference<Preference>("privacy_policy")?.setOnPreferenceClickListener {
                val url = "https://codenzi.com/snapnote"
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Could not open browser for privacy policy", e)
                    Toast.makeText(requireContext(), getString(R.string.toast_no_browser_found), Toast.LENGTH_SHORT).show()
                }
                true
            }

            findPreference<Preference>("contact_us")?.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:".toUri()
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("info@codenzi.com"))
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.contact_us_email_subject))
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Could not open email client", e)
                    Toast.makeText(requireContext(), getString(R.string.toast_no_email_app_found), Toast.LENGTH_SHORT).show()
                }
                true
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
                        Toast.makeText(requireContext(), getString(R.string.no_notes_to_backup), Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val tempDir = File(requireContext().cacheDir, "backup_temp").apply { mkdirs() }
                val filesToZip = mutableListOf<File>()

                // Medya dosyalarını geçici dizine kopyala
                val notesForJson = notesToBackup.map { note ->
                    val content = gson.fromJson(note.content, NoteContent::class.java)
                    var newImagePath: String? = null
                    content.imagePath?.let { path ->
                        val sourceFile = File(path)
                        if (sourceFile.exists()) {
                            val destFile = File(tempDir, sourceFile.name)
                            sourceFile.copyTo(destFile, overwrite = true)
                            filesToZip.add(destFile)
                            newImagePath = sourceFile.name // ZIP içindeki göreceli yol
                        }
                    }
                    var newAudioPath: String? = null
                    content.audioFilePath?.let { path ->
                        val sourceFile = File(path)
                        if (sourceFile.exists()) {
                            val destFile = File(tempDir, sourceFile.name)
                            sourceFile.copyTo(destFile, overwrite = true)
                            filesToZip.add(destFile)
                            newAudioPath = sourceFile.name // ZIP içindeki göreceli yol
                        }
                    }
                    val newContent = content.copy(imagePath = newImagePath, audioFilePath = newAudioPath)
                    note.copy(content = gson.toJson(newContent))
                }

                // JSON dosyasını oluştur
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val appSettings = AppSettings(
                    themeSelection = sharedPrefs.getString("theme_selection", "system_default"),
                    colorSelection = sharedPrefs.getString("color_selection", "bordo"),
                    widgetBackgroundSelection = sharedPrefs.getString("widget_background_selection", "widget_background")
                )
                val passwordHash = PasswordManager.getPasswordHash()
                val salt = PasswordManager.getSalt()

                val backupData = BackupData(
                    settings = appSettings,
                    notes = notesForJson,
                    passwordHash = passwordHash,
                    salt = salt
                )
                val jsonFile = File(tempDir, "backup.json")
                jsonFile.writeText(gson.toJson(backupData))
                filesToZip.add(jsonFile)

                // ZIP dosyasını oluştur
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
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

                // Geçici dosyaları temizle
                tempDir.deleteRecursively()

                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), getString(R.string.export_successful), Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), getString(R.string.export_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        private suspend fun importFromDevice(uri: Uri) {
            val tempDir = File(requireContext().cacheDir, "import_temp").apply { mkdirs() }

            try {
                // ZIP dosyasını aç
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
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

                // JSON dosyasını oku
                val jsonFile = File(tempDir, "backup.json")
                if (!jsonFile.exists()) {
                    throw IOException("backup.json not found in ZIP file.")
                }
                val backupData: BackupData = gson.fromJson(jsonFile.readText(), object : TypeToken<BackupData>() {}.type)

                // Şifre kontrolü veya geri yükleme onayı
                withContext(Dispatchers.Main) {
                    if (backupData.passwordHash != null && backupData.salt != null) {
                        showPasswordPromptForLocalRestore(backupData, tempDir)
                    } else {
                        showLocalRestoreConfirmationDialog(backupData, tempDir)
                    }
                }

            } catch (e: Exception) {
                tempDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), getString(R.string.import_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun showLocalRestoreConfirmationDialog(backupData: BackupData, tempDir: File) {
            AlertDialog.Builder(requireContext())
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
            val editText = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.enter_current_password_hint)
            }

            AlertDialog.Builder(requireContext())
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
                        Toast.makeText(requireContext(), getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show()
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
                // Mevcut verileri temizle
                noteDao.deleteAllNotes()

                // Medya dosyalarını kalıcı konuma taşı
                val restoredNotes = backupData.notes.map { note ->
                    val content = gson.fromJson(note.content, NoteContent::class.java)
                    var newImagePath: String? = null
                    content.imagePath?.let { fileName ->
                        val sourceFile = File(tempDir, fileName)
                        if (sourceFile.exists()) {
                            val permanentDir = requireContext().getExternalFilesDir("Images")!!
                            val permanentFile = File(permanentDir, fileName)
                            sourceFile.copyTo(permanentFile, overwrite = true)
                            newImagePath = permanentFile.absolutePath
                        }
                    }
                    var newAudioPath: String? = null
                    content.audioFilePath?.let { fileName ->
                        val sourceFile = File(tempDir, fileName)
                        if(sourceFile.exists()){
                            val permanentDir = requireContext().getExternalFilesDir("AudioNotes")!!
                            val permanentFile = File(permanentDir, fileName)
                            sourceFile.copyTo(permanentFile, overwrite = true)
                            newAudioPath = permanentFile.absolutePath
                        }
                    }
                    val newContent = content.copy(imagePath = newImagePath, audioFilePath = newAudioPath)
                    note.copy(content = gson.toJson(newContent))
                }

                noteDao.insertAll(restoredNotes)

                // Ayarları uygula
                val prefsEditor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                prefsEditor.putString("theme_selection", backupData.settings.themeSelection)
                prefsEditor.putString("color_selection", backupData.settings.colorSelection)
                prefsEditor.putString("widget_background_selection", backupData.settings.widgetBackgroundSelection)
                prefsEditor.apply()

                // Şifreyi geri yükle
                if (backupData.passwordHash != null && backupData.salt != null) {
                    PasswordManager.resetForRestore(requireContext())
                    PasswordManager.restorePassword(backupData.passwordHash, backupData.salt)
                }

                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), getString(R.string.import_successful), Toast.LENGTH_LONG).show()
                    activity?.recreate()
                }

            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), getString(R.string.import_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }

        private fun showDriveRestoreConfirmationDialog(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.restore_dialog_title))
                .setMessage(getString(R.string.restore_from_drive_dialog_message))
                .setPositiveButton(getString(R.string.restore_confirm)) { _, _ ->
                    lifecycleScope.launch {
                        proceedWithRestore(googleDriveManager, backupData)
                    }
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
        }

        private fun showDeleteAccountConfirmationDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_account_title)
                .setMessage(R.string.delete_account_confirmation_message)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    showFinalDeleteConfirmationDialog()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun showFinalDeleteConfirmationDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.final_delete_confirmation_title))
                .setMessage(getString(R.string.final_delete_confirmation_message))
                .setPositiveButton(getString(R.string.final_delete_confirm_button)) { _, _ ->
                    val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(requireContext())
                    if (lastSignedInAccount == null) {
                        showProgressDialog(R.string.delete_in_progress)
                        DataWipeManager.wipeAllData(requireContext())
                    } else {
                        requestedAction = Action.DELETE
                        signInToGoogle()
                    }
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
        }

        @Suppress("DEPRECATION")
        private fun signInToGoogle() {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
                .build()

            val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        @Suppress("DEPRECATION")
        private fun handleSignInResult(data: Intent?) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)!!

                val credential = GoogleAccountCredential.usingOAuth2(
                    requireContext(),
                    listOf("https://www.googleapis.com/auth/drive.appdata")
                ).setSelectedAccount(account.account)

                val googleDriveManager = GoogleDriveManager(credential)

                when (requestedAction) {
                    Action.BACKUP -> backupNotes(googleDriveManager)
                    Action.RESTORE -> restoreNotes(googleDriveManager)
                    Action.DELETE -> performAccountDeletion(googleDriveManager)
                    else -> {}
                }
            } catch (e: ApiException) {
                Log.w("SettingsFragment", "signInResult:failed code=" + e.statusCode, e)
                Toast.makeText(requireContext(), getString(R.string.sign_in_error_try_again), Toast.LENGTH_LONG).show()
            }
        }

        private fun performAccountDeletion(googleDriveManager: GoogleDriveManager) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        showProgressDialog(R.string.delete_in_progress)
                    }

                    googleDriveManager.deleteFile("snapnote_backup.json")

                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                    val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
                    googleSignInClient.revokeAccess().await()

                    withContext(Dispatchers.Main) {
                        if (!DataWipeManager.wipeAllData(requireContext())) {
                            dismissProgressDialog()
                            Toast.makeText(requireContext(), "Hesap silinemedi.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                        Toast.makeText(requireContext(), getString(R.string.account_deletion_failed_with_error, e.message), Toast.LENGTH_LONG).show()
                    }
                    Log.e("SettingsFragment", "Account deletion failed", e)
                }
            }
        }

        private fun showAddWidgetDialog() {
            val widgetOptions = arrayOf(
                getString(R.string.widget_option_note_list),
                getString(R.string.widget_option_camera_note),
                getString(R.string.widget_option_voice_note)
            )
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.add_widget_dialog_title))
                .setItems(widgetOptions) { _, which ->
                    val componentName = when (which) {
                        0 -> ComponentName(requireActivity(), NoteWidgetProvider::class.java)
                        1 -> ComponentName(requireActivity(), CameraWidgetProvider::class.java)
                        2 -> ComponentName(requireActivity(), VoiceMemoWidgetProvider::class.java)
                        else -> null
                    }
                    componentName?.let { requestPinWidget(it) }
                }
                .show()
        }

        private fun requestPinWidget(componentName: ComponentName) {
            val appWidgetManager = AppWidgetManager.getInstance(requireContext())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (appWidgetManager.isRequestPinAppWidgetSupported) {
                    appWidgetManager.requestPinAppWidget(componentName, null, null)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.launcher_does_not_support_feature), Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.feature_requires_android_oreo), Toast.LENGTH_LONG).show()
            }
        }

        private fun updateAllWidgets() {
            val context = context?.applicationContext ?: return
            val appWidgetManager = AppWidgetManager.getInstance(context)

            val noteWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, NoteWidgetProvider::class.java))
            if (noteWidgetIds.isNotEmpty()) {
                val noteIntent = Intent(context, NoteWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, noteWidgetIds)
                }
                context.sendBroadcast(noteIntent)
            }

            val cameraWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, CameraWidgetProvider::class.java))
            if (cameraWidgetIds.isNotEmpty()) {
                val cameraIntent = Intent(context, CameraWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, cameraWidgetIds)
                }
                context.sendBroadcast(cameraIntent)
            }

            val voiceMemoWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, VoiceMemoWidgetProvider::class.java))
            if (voiceMemoWidgetIds.isNotEmpty()) {
                val voiceMemoIntent = Intent(context, VoiceMemoWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, voiceMemoWidgetIds)
                }
                context.sendBroadcast(voiceMemoIntent)
            }
        }

        private fun showProgressDialog(titleResId: Int) {
            val builder = AlertDialog.Builder(requireContext())
            val inflater = requireActivity().layoutInflater
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

        private fun backupNotes(googleDriveManager: GoogleDriveManager) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val localNotes = noteDao.getAllNotes().first()

                    if (localNotes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), getString(R.string.no_notes_to_backup), Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val existingBackup = googleDriveManager.getBackupFiles()?.firstOrNull()

                    withContext(Dispatchers.Main) {
                        if (existingBackup != null) {
                            AlertDialog.Builder(requireContext())
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
                            proceedWithBackup(googleDriveManager, localNotes)
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

            try {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val appSettings = AppSettings(
                    themeSelection = sharedPrefs.getString("theme_selection", "system_default"),
                    colorSelection = sharedPrefs.getString("color_selection", "bordo"),
                    widgetBackgroundSelection = sharedPrefs.getString("widget_background_selection", "widget_background")
                )

                val passwordHash = if (PasswordManager.isPasswordSet()) PasswordManager.getPasswordHash() else null
                val salt = if (PasswordManager.isPasswordSet()) PasswordManager.getSalt() else null

                val notesForBackup = mutableListOf<Note>()
                val totalSteps = notesToBackup.size + 1

                notesToBackup.forEachIndexed { index, note ->
                    val content = gson.fromJson(note.content, NoteContent::class.java)
                    var imageDriveId: String? = null

                    content.imagePath?.let { path ->
                        val imageFile = File(path)
                        if (imageFile.exists()) {
                            imageDriveId = googleDriveManager.uploadMediaFile(imageFile, "image/jpeg")
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
                        Toast.makeText(requireContext(), getString(R.string.backup_successful), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.an_error_occurred_during_backup_simple), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                }
                showError("Backup failed", e)
            }
        }

        private fun restoreNotes(googleDriveManager: GoogleDriveManager) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        showProgressDialog(R.string.searching_for_backups)
                    }

                    val backupFile = googleDriveManager.getBackupFiles()?.firstOrNull()
                    if (backupFile == null) {
                        withContext(Dispatchers.Main) {
                            dismissProgressDialog()
                            Toast.makeText(requireContext(), getString(R.string.backup_not_found), Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val jsonContent = googleDriveManager.downloadJsonBackup(backupFile.id)
                    if (jsonContent.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            dismissProgressDialog()
                            Toast.makeText(requireContext(), getString(R.string.backup_file_empty_or_corrupt), Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val type = object : TypeToken<BackupData>() {}.type
                    val backupData: BackupData = gson.fromJson(jsonContent, type)

                    if (backupData.notes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            dismissProgressDialog()
                            Toast.makeText(requireContext(), "Yedek dosyası bulundu ancak içerisinde geri yüklenecek not yok.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                        if (backupData.passwordHash != null && backupData.salt != null) {
                            showPasswordPromptForRestore(googleDriveManager, backupData)
                        } else {
                            showDriveRestoreConfirmationDialog(googleDriveManager, backupData)
                        }
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                    }
                    showError(getString(R.string.restore_failed), e)
                }
            }
        }

        private fun showPasswordPromptForRestore(googleDriveManager: GoogleDriveManager, backupData: BackupData) {
            val editText = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.enter_current_password_hint)
            }

            AlertDialog.Builder(requireContext())
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
                            Toast.makeText(requireContext(), getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show()
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
                        if (googleDriveManager.downloadMediaFile(driveId, imageFile)) {
                            localImagePath = imageFile.absolutePath
                            tempFiles.add(imageFile)
                        } else {
                            isDownloadSuccessful = false
                        }
                    }
                    var localAudioPath: String? = null
                    content.audioFilePath?.let { driveId ->
                        val audioFile = createAudioFile()
                        if (googleDriveManager.downloadMediaFile(driveId, audioFile)) {
                            localAudioPath = audioFile.absolutePath
                            tempFiles.add(audioFile)
                        } else {
                            isDownloadSuccessful = false
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

                    val prefsEditor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    prefsEditor.putString("theme_selection", backupData.settings.themeSelection)
                    prefsEditor.putString("color_selection", backupData.settings.colorSelection)
                    prefsEditor.putString("widget_background_selection", backupData.settings.widgetBackgroundSelection)
                    prefsEditor.apply()

                    if (backupData.passwordHash != null && backupData.salt != null) {
                        PasswordManager.resetForRestore(requireContext())
                        PasswordManager.restorePassword(backupData.passwordHash, backupData.salt)
                    }

                    withContext(Dispatchers.Main) {
                        updateProgress(100)
                        dismissProgressDialog()
                        Toast.makeText(requireContext(), getString(R.string.restore_success), Toast.LENGTH_LONG).show()
                        activity?.recreate()
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
            val context = requireContext()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val storageDir: File = context.getExternalFilesDir("RestoredImages") ?: context.filesDir
            storageDir.mkdirs()
            return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
        }

        @Throws(IOException::class)
        private fun createAudioFile(): File {
            val context = requireContext()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val storageDir: File = context.getExternalFilesDir("RestoredAudio") ?: context.filesDir
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
                Toast.makeText(requireContext(), finalMessage, Toast.LENGTH_LONG).show()
                Log.e("SettingsFragment", message, e)
            }
        }
    }
}