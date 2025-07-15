package com.codenzi.snapnote

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.codenzi.snapnote.databinding.ActivityTrashBinding
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class TrashActivity : AppCompatActivity() {

    @Inject
    lateinit var noteDao: NoteDao

    private lateinit var binding: ActivityTrashBinding
    private lateinit var deletedNoteAdapter: NoteAdapter

    private var isSelectionMode = false
    private var currentThemeResId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        currentThemeResId = ThemeManager.getThemeResId(this)

        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarTrash)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toolbarTrash.setNavigationOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        setupRecyclerView()
        observeDeletedNotes()
        setupBackButtonHandler()
    }

    override fun onResume() {
        super.onResume()
        if (currentThemeResId != ThemeManager.getThemeResId(this)) {
            recreate()
        }
    }

    private fun setupRecyclerView() {
        deletedNoteAdapter = NoteAdapter(emptyList(),
            { note ->
                if (isSelectionMode) {
                    toggleSelection(note)
                } else {
                    showSingleNoteOptionsDialog(note)
                }
            },
            { note ->
                if (!isSelectionMode) {
                    enterSelectionMode()
                }
                toggleSelection(note)
            }
        )
        binding.rvDeletedNotes.adapter = deletedNoteAdapter
        binding.rvDeletedNotes.layoutManager = LinearLayoutManager(this)
    }

    private fun observeDeletedNotes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteDao.getDeletedNotes().collect { notes ->
                    deletedNoteAdapter.updateNotes(notes)
                    if (notes.isEmpty()) {
                        binding.tvEmptyTrash.visibility = View.VISIBLE
                        binding.rvDeletedNotes.visibility = View.GONE
                    } else {
                        binding.tvEmptyTrash.visibility = View.GONE
                        binding.rvDeletedNotes.visibility = View.VISIBLE
                    }
                    invalidateOptionsMenu()

                    if (notes.isEmpty() && isSelectionMode) {
                        exitSelectionMode()
                    }
                }
            }
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        invalidateOptionsMenu()
        binding.toolbarTrash.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        deletedNoteAdapter.clearSelections()
        invalidateOptionsMenu()
        supportActionBar?.title = getString(R.string.trash_title)
        binding.toolbarTrash.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back)
    }

    private fun toggleSelection(note: Note) {
        deletedNoteAdapter.toggleSelection(note.id)
        val count = deletedNoteAdapter.getSelectedItemCount()
        if (count == 0) {
            exitSelectionMode()
        } else {
            supportActionBar?.title = resources.getQuantityString(R.plurals.selection_title, count, count)
            invalidateOptionsMenu()
        }
    }

    private fun showSingleNoteOptionsDialog(note: Note) {
        val options = arrayOf(getString(R.string.restore_note), getString(R.string.delete_permanently))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.note_options_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> restoreNote(note)
                    1 -> showPermanentDeleteConfirmationDialog(listOf(note))
                }
            }
            .show()
    }

    private fun restoreNote(note: Note) {
        lifecycleScope.launch {
            noteDao.restoreNotes(listOf(note.id))
            Toast.makeText(applicationContext, getString(R.string.note_restored_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermanentDeleteConfirmationDialog(notesToDelete: List<Note>) {
        if (notesToDelete.isEmpty()) return

        val message = if (notesToDelete.size == 1) {
            getString(R.string.dialog_message_delete_permanently)
        } else {
            resources.getQuantityString(R.plurals.delete_notes_confirmation_message, notesToDelete.size, notesToDelete.size)
        }

        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_notes_confirmation_title, notesToDelete.size, notesToDelete.size))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_confirm_delete_permanently)) { _, _ ->
                permanentDeleteNotes(notesToDelete)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun permanentDeleteNotes(notes: List<Note>) {
        lifecycleScope.launch {
            val gson = Gson()
            for (note in notes) {
                try {
                    val content = gson.fromJson(note.content, NoteContent::class.java)

                    content.imagePath?.let { path -> deleteFileFromPath(path) }
                    content.audioFilePath?.let { path -> deleteFileFromPath(path) }

                } catch (e: JsonSyntaxException) {
                    Log.e("TrashActivity", "Error parsing note content while deleting", e)
                } catch (e: Exception) {
                    Log.e("TrashActivity", "Error deleting files for note ${note.id}", e)
                }
            }

            val noteIds = notes.map { it.id }
            noteDao.hardDeleteByIds(noteIds)
            Toast.makeText(applicationContext, resources.getQuantityString(R.plurals.notes_deleted_toast, notes.size, notes.size), Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }

    private fun deleteFileFromPath(path: String) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                // URI'den dosya silme (özellikle FileProvider URI'leri için)
                contentResolver.delete(uri, null, null)
            } else {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("TrashActivity", "Failed to delete file: $path", e)
        }
    }

    private fun setupBackButtonHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.trash_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasNotes = deletedNoteAdapter.itemCount > 0
        menu.findItem(R.id.action_trash_info).isVisible = hasNotes && !isSelectionMode
        menu.findItem(R.id.action_delete_selected).isVisible = isSelectionMode && deletedNoteAdapter.getSelectedItemCount() > 0
        menu.findItem(R.id.action_select_all).isVisible = isSelectionMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_trash_info -> {
                showTrashInfoDialog()
                true
            }
            R.id.action_select_all -> {
                deletedNoteAdapter.selectAll()
                val count = deletedNoteAdapter.itemCount
                supportActionBar?.title = resources.getQuantityString(R.plurals.selection_title, count, count)
                invalidateOptionsMenu()
                true
            }
            R.id.action_delete_selected -> {
                showPermanentDeleteConfirmationDialog(deletedNoteAdapter.getSelectedNotes())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTrashInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.trash_bin_title)
            .setMessage(R.string.trash_info_message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}