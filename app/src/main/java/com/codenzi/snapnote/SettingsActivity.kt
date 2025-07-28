package com.codenzi.snapnote

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

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

            findPreference<Preference>("password_settings")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PasswordSettingsActivity::class.java))
                true
            }

            findPreference<Preference>("trash_settings")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), TrashActivity::class.java))
                true
            }

            findPreference<Preference>("privacy_policy")?.setOnPreferenceClickListener {
                val url = "https://www.codenzi.com/snapnote-privacy-policy.html"
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Could not open browser for privacy policy", e)
                    Toast.makeText(requireContext(), getString(R.string.toast_no_browser_found), Toast.LENGTH_SHORT).show()
                }
                true
            }

            findPreference<Preference>("term_of_use")?.setOnPreferenceClickListener {
                val url = "https://www.codenzi.com/snapnote-term-of-use.html"
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Could not open browser for term of use", e)
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
    }
}