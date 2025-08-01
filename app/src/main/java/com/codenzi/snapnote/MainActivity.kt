package com.codenzi.snapnote

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.codenzi.snapnote.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var noteAdapter: NoteAdapter
    private var isSelectionMode = false
    private var currentThemeResId: Int = 0

    companion object {
        private const val PREF_THEME_MODE = "theme_selection"
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, getString(R.string.mic_permission_granted_widget_available), Toast.LENGTH_SHORT).show()
            updateVoiceWidgets()
        } else {
            Toast.makeText(this, getString(R.string.mic_permission_denied_widget_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        applySavedTheme()
        super.onCreate(savedInstanceState)
        currentThemeResId = ThemeManager.getThemeResId(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.decorView.setBackgroundColor(getColorFromAttr(com.google.android.material.R.attr.colorSurface))

        setSupportActionBar(binding.toolbar)
        setupRecyclerView()

        binding.fabAddNote.setOnClickListener {
            startActivity(Intent(this, NoteActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notes.collect { notes ->
                    noteAdapter.submitList(notes) // <-- DOĞRU KOD BU
                    binding.tvEmptyNotes.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvNotes.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish() // Geri tuşuna basıldığında uygulamayı kapatır.
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)

        checkAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        if (currentThemeResId != ThemeManager.getThemeResId(this)) {
            recreate()
        }
    }

    private fun getColorFromAttr(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    private fun applySavedTheme() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themeModeString = sharedPrefs.getString(PREF_THEME_MODE, "system_default")
        val mode = when (themeModeString) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setupRecyclerView() {
        // --- HATA DÜZELTME BAŞLANGICI ---
        // NoteAdapter'ı yeni kurucu metoda (constructor) göre başlatıyoruz.
        // Artık başlangıçta boş liste göndermiyoruz ve long-click lambda'sının 'true' döndürmesini sağlıyoruz.
        noteAdapter = NoteAdapter(
            clickListener = { note ->
                if (isSelectionMode) {
                    toggleSelection(note)
                } else {
                    val intent = Intent(this, NoteActivity::class.java).apply {
                        putExtra("NOTE_ID", note.id)
                    }
                    startActivity(intent)
                }
            },
            longClickListener = { note ->
                if (!isSelectionMode) {
                    enterSelectionMode()
                }
                toggleSelection(note)
                true // Uzun basma olayını tükettiğimizi belirtmek için true döndür.
            }
        )
        // --- HATA DÜZELTME SONU ---

        binding.rvNotes.adapter = noteAdapter
        binding.rvNotes.layoutManager = LinearLayoutManager(this)
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        invalidateOptionsMenu()
        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        binding.toolbar.setNavigationOnClickListener { exitSelectionMode() }
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        noteAdapter.clearSelections()
        invalidateOptionsMenu()
        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.navigationIcon = null
    }

    private fun toggleSelection(note: Note) {
        noteAdapter.toggleSelection(note.id)
        val count = noteAdapter.getSelectedItemCount()
        if (count == 0) {
            exitSelectionMode()
        } else {
            binding.toolbar.title = resources.getQuantityString(R.plurals.selection_title, count, count)
            invalidateOptionsMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView

        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
        })

        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.setSearchQuery("")
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val pinItem = menu.findItem(R.id.action_pin_to_widget)
        menu.findItem(R.id.action_search).isVisible = !isSelectionMode
        menu.findItem(R.id.action_sort).isVisible = !isSelectionMode
        menu.findItem(R.id.action_settings).isVisible = !isSelectionMode
        pinItem.isVisible = isSelectionMode
        menu.findItem(R.id.action_share_contextual).isVisible = isSelectionMode
        menu.findItem(R.id.action_delete_contextual).isVisible = isSelectionMode
        menu.findItem(R.id.action_select_all).isVisible = isSelectionMode

        if (isSelectionMode) {
            val selectedNotes = noteAdapter.getSelectedNotes()
            val areAllSelectedPinned = selectedNotes.isNotEmpty() && selectedNotes.all { it.showOnWidget }

            if (areAllSelectedPinned) {
                pinItem.title = getString(R.string.unpin_from_widget)
                pinItem.icon = ContextCompat.getDrawable(this, R.drawable.ic_pin_off)
            } else {
                pinItem.title = getString(R.string.pin_to_widget)
                pinItem.icon = ContextCompat.getDrawable(this, R.drawable.ic_push_pin)
            }

            val searchItem = menu.findItem(R.id.action_search)
            if (searchItem.isActionViewExpanded) {
                searchItem.collapseActionView()
            }
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val selectedNotes = noteAdapter.getSelectedNotes()
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_select_all -> {
                noteAdapter.selectAll()
                val count = noteAdapter.itemCount
                binding.toolbar.title = resources.getQuantityString(R.plurals.selection_title, count, count)
                invalidateOptionsMenu()
                true
            }
            R.id.action_pin_to_widget -> {
                val areAllSelectedPinned = selectedNotes.isNotEmpty() && selectedNotes.all { it.showOnWidget }
                pinNotesToWidget(selectedNotes, !areAllSelectedPinned)
                exitSelectionMode()
                true
            }
            R.id.action_share_contextual -> {
                shareNotes(selectedNotes)
                exitSelectionMode()
                true
            }
            R.id.action_delete_contextual -> {
                deleteNotes(selectedNotes)
                exitSelectionMode()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateAllWidgets() {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val componentName = ComponentName(applicationContext, NoteWidgetProvider::class.java)
            appWidgetManager.getAppWidgetIds(componentName).forEach { appWidgetId ->
                NoteWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, appWidgetId)
            }
        } catch (_: Exception) {
            Toast.makeText(applicationContext, "An error occurred while updating the widget.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pinNotesToWidget(notes: List<Note>, pin: Boolean) {
        if (notes.isEmpty()) return
        viewModel.setPinnedStatus(notes, pin)
        updateAllWidgets()
        val message = if (pin) R.string.notes_pinned_to_widget_toast else R.string.unpinned_from_widget_toast
        Toast.makeText(applicationContext, getString(message), Toast.LENGTH_SHORT).show()
    }


    private fun Note.toSharableString(): String {
        val gson = Gson()
        val builder = StringBuilder()
        if (this.title.isNotBlank()) {
            builder.append(this.title).append("\n\n")
        }
        try {
            val noteContent = gson.fromJson(this.content, NoteContent::class.java)
            if (noteContent.text.isNotBlank()) {
                val plainText = Html.fromHtml(noteContent.text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                builder.append(plainText).append("\n\n")
            }
            if (noteContent.checklist.isNotEmpty()) {
                noteContent.checklist.forEach { item ->
                    val checkbox = if (item.isChecked) "✓" else "☐"
                    builder.append("$checkbox ${item.text}\n")
                }
                builder.append("\n")
            }
        } catch (_: JsonSyntaxException) {
            val plainText = Html.fromHtml(this.content, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            builder.append(plainText)
        }
        return builder.toString().trim()
    }

    private fun shareNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        if (notes.size == 1) {
            val note = notes.first()
            val noteTitle = note.title.ifBlank { getString(R.string.shared_note_default_title) }
            val noteBitmap = createBitmapFromNote(note)

            val urisToShare = ArrayList<Uri>()

            noteBitmap?.let { bmp ->
                saveBitmapToCache(bmp)?.let { imageFile ->
                    val imageUri = FileProvider.getUriForFile(this, "$packageName.provider", imageFile)
                    urisToShare.add(imageUri)
                }
            }

            try {
                val gson = Gson()
                val noteContent = gson.fromJson(note.content, NoteContent::class.java)
                val audioPath = noteContent.audioFilePath
                if (audioPath != null) {
                    val audioFile = File(audioPath)
                    if (audioFile.exists()) {
                        val audioUri = FileProvider.getUriForFile(this, "$packageName.provider", audioFile)
                        urisToShare.add(audioUri)
                    }
                }
            } catch (_: Exception) {}

            if (urisToShare.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisToShare)

                    val clipData = ClipData.newUri(contentResolver, noteTitle, urisToShare.first())
                    if (urisToShare.size > 1) {
                        for (i in 1 until urisToShare.size) {
                            clipData.addItem(ClipData.Item(urisToShare[i]))
                        }
                    }
                    setClipData(clipData)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_note_chooser_title)))

            } else if (noteBitmap == null) {
                Toast.makeText(this, getString(R.string.note_too_long_for_image_share_toast), Toast.LENGTH_LONG).show()
                val plainText = note.toSharableString()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(noteTitle, plainText)
                clipboard.setPrimaryClip(clip)
            }

        } else {
            val shareText = notes.joinToString("\n\n---\n\n") { it.toSharableString() }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_notes_chooser_title)))
        }
    }

    private fun createBitmapFromNote(note: Note): Bitmap? {
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.note_render_layout, FrameLayout(this), false)
            val titleView = view.findViewById<TextView>(R.id.render_note_title)
            val contentView = view.findViewById<TextView>(R.id.render_note_content)
            val imageView = view.findViewById<ImageView>(R.id.render_note_image)

            val backgroundColor = try {
                note.color.toColorInt()
            } catch (_: Exception) {
                Color.WHITE
            }
            view.setBackgroundColor(backgroundColor)

            val textColor = getContrastingTextColor(backgroundColor)
            titleView.setTextColor(textColor)
            contentView.setTextColor(textColor)
            contentView.setLinkTextColor(textColor)

            val gson = Gson()
            val noteContent = gson.fromJson(note.content, NoteContent::class.java)

            if (note.title.isNotBlank()) {
                titleView.visibility = View.VISIBLE
                titleView.text = note.title
            } else {
                titleView.visibility = View.GONE
            }

            if (noteContent.imagePath != null) {
                try {
                    val path = noteContent.imagePath
                    val imageBitmap: Bitmap?

                    if (path.startsWith("content://")) {
                        val imageUri = Uri.parse(path)
                        val inputStream = contentResolver.openInputStream(imageUri)
                        imageBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                    } else {
                        val imageFile = File(path)
                        imageBitmap = if (imageFile.exists()) {
                            BitmapFactory.decodeFile(imageFile.absolutePath)
                        } else {
                            null
                        }
                    }

                    if (imageBitmap != null) {
                        imageView.setImageBitmap(imageBitmap)
                        imageView.visibility = View.VISIBLE
                    } else {
                        imageView.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    imageView.visibility = View.GONE
                    Log.e("MainActivity", "Error loading image for sharing", e)
                }
            } else {
                imageView.visibility = View.GONE
            }

            val contentBuilder = StringBuilder()
            if (noteContent.text.isNotBlank()) {
                contentBuilder.append(noteContent.text)
            }
            if (noteContent.checklist.isNotEmpty()) {
                contentBuilder.append("<br><b>${getString(R.string.checklist_render_title)}</b><br>")
                noteContent.checklist.forEach { item ->
                    val checkbox = if (item.isChecked) "✓" else "☐"
                    val text = Html.escapeHtml(item.text)
                    contentBuilder.append(if (item.isChecked) "$checkbox <s>$text</s><br>" else "$checkbox $text<br>")
                }
            }
            contentView.text = Html.fromHtml(contentBuilder.toString(), Html.FROM_HTML_MODE_COMPACT)

            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.9).toInt()

            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            if (view.measuredHeight > 8192) {
                return null
            }
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            return view.drawToBitmap()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating bitmap from note", e)
            return null
        }
    }

    private fun getContrastingTextColor(backgroundColor: Int): Int {
        val luma = (0.299 * Color.red(backgroundColor) + 0.587 * Color.green(backgroundColor) + 0.114 * Color.blue(backgroundColor)) / 255
        return if (luma > 0.5) Color.BLACK else Color.WHITE
    }

    private fun saveBitmapToCache(bitmap: Bitmap): File? {
        return try {
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()
            val file = File(cachePath, "note_to_share.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()
            file
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving bitmap to cache", e)
            null
        }
    }

    private fun deleteNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.move_notes_to_trash_confirmation_title, notes.size, notes.size))
            .setMessage(getString(R.string.move_notes_to_trash_confirmation_message))
            .setPositiveButton(getString(R.string.dialog_move_to_trash)) { _, _ ->
                viewModel.moveNotesToTrash(notes)
                Toast.makeText(applicationContext, resources.getQuantityString(R.plurals.notes_deleted_toast, notes.size, notes.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_by_creation_date_newest),
            getString(R.string.sort_by_creation_date_oldest),
            getString(R.string.sort_by_content_az)
        )
        val checkedItem = viewModel.sortOrder.value.ordinal
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_dialog_title))
            .setSingleChoiceItems(sortOptions, checkedItem) { dialog, which ->
                viewModel.setSortOrder(MainViewModel.SortOrder.entries[which])
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun checkAudioPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateVoiceWidgets() {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val componentName = ComponentName(applicationContext, VoiceMemoWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val intent = Intent(applicationContext, VoiceMemoWidgetProvider::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            applicationContext.sendBroadcast(intent)
        } catch (_: Exception) {
            // Hata durumunda kullanıcıyı rahatsız etmemek için sessiz kal
        }
    }
}