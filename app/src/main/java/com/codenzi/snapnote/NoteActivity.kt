package com.codenzi.snapnote

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.codenzi.snapnote.databinding.ActivityNoteBinding
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects

@AndroidEntryPoint
class NoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteBinding
    private val viewModel: NoteViewModel by viewModels()
    private var currentNoteId: Int? = null

    private lateinit var colorPickers: List<View>
    private var selectedColor: String = "#FFECEFF1"

    private lateinit var checklistAdapter: ChecklistItemAdapter
    private var checklistItems = mutableListOf<ChecklistItem>()

    private val gson = Gson()
    private var isUpdatingToggleButtons = false

    // Medya ve Sesle Yazma
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent
    private var isListening = false
    private val recognizedTextBuilder = StringBuilder()
    private var utteranceStartPosition = 0
    private val restartHandler = Handler(Looper.getMainLooper())

    // --- GÜNCELLENMİŞ DOSYA YÖNETİMİ (URI TABANLI) ---
    // Artık mutlak dosya yolu (String) yerine güvenli URI (Uri) kullanıyoruz.
    private var audioPath: String? = null // Ses dosyaları için path kullanımı şimdilik kalabilir.
    private var imageUri: Uri? = null      // Resimler için ana değişkenimiz bu olacak.
    private var tempPhotoUri: Uri? = null  // Kameradan gelen geçici resmi tutmak için.

    private var isFromWidget = false

    // Değişiklik kontrolü için kullanılan veri sınıfı.
    private data class NoteState(
        val title: String,
        val contentHtml: String,
        val checklist: List<ChecklistItem>,
        val color: String,
        val imageUriString: String?,
        val audioPath: String?
    ) {
        // equals ve hashCode metodları, içeriğin gerçekten değişip değişmediğini doğru anlamak için override edildi.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as NoteState
            return title == other.title &&
                    contentHtml == other.contentHtml &&
                    color == other.color &&
                    imageUriString == other.imageUriString &&
                    audioPath == other.audioPath &&
                    checklist.size == other.checklist.size &&
                    checklist.indices.all { i -> checklist[i] == other.checklist[i] }
        }
        override fun hashCode(): Int = Objects.hash(title, contentHtml, checklist, color, imageUriString, audioPath)
    }
    private var initialNoteState: NoteState? = null

    companion object {
        // Ekran döndürme gibi durumlarda geçici URI'nin kaybolmaması için anahtar.
        private const val KEY_TEMP_PHOTO_URI = "KEY_TEMP_PHOTO_URI"
    }

    // İzin ve Aktivite Sonuçları için modern launcher'lar.
    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) takePicture() else Toast.makeText(this, "Kamera izni gerekli.", Toast.LENGTH_SHORT).show()
    }
    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startRecording() else Toast.makeText(this, "Mikrofon izni gerekli.", Toast.LENGTH_SHORT).show()
    }
    private val requestVoiceToTextPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) toggleSpeechToText() else Toast.makeText(this, getString(R.string.microphone_permission_needed), Toast.LENGTH_SHORT).show()
    }

    // Kamera aktivitesinden sonuç döndüğünde tetiklenir.
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = tempPhotoUri
        if (success && capturedUri != null) {
            imageUri = capturedUri // Yakalanan geçici URI'yi ana URI değişkenimize atıyoruz.
            binding.ivImagePreview.visibility = View.VISIBLE
            binding.ivImagePreview.load(imageUri) // Coil kütüphanesi ile URI'yi yüklüyoruz.

            if (binding.etNoteTitle.text!!.isBlank()) {
                val titleWithTimestamp = "${getString(R.string.photo_note_title)} - ${formatDate(System.currentTimeMillis(), "dd/MM/yyyy HH:mm")}"
                binding.etNoteTitle.setText(titleWithTimestamp)
            }
        } else {
            // İşlem başarısızsa veya kullanıcı iptal ederse, URI'leri temizliyoruz.
            imageUri = null
            tempPhotoUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Eğer uygulama yeniden oluşturulduysa (örn. ekran döndürme), geçici URI'yi geri yüklüyoruz.
        savedInstanceState?.getString(KEY_TEMP_PHOTO_URI)?.let { uriString ->
            tempPhotoUri = Uri.parse(uriString)
            imageUri = tempPhotoUri
        }

        binding = ActivityNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundColor(getColorFromAttr(com.google.android.material.R.attr.colorSurface))

        setupListeners()
        setupChecklist()
        setupColorPickers()
        setupVoiceToText()
        observeViewModel()

        processIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                performSave()
            }
        })
    }

    // Ekran döndürme gibi durumlarda geçici veriyi kaydetmek için.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tempPhotoUri?.let { outState.putString(KEY_TEMP_PHOTO_URI, it.toString()) }
    }

    private fun getColorFromAttr(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    override fun onStop() {
        super.onStop()
        releaseMediaPlayer()
        if (isRecording) stopRecording()
        if (isListening) stopListening()
    }

    private fun observeViewModel() {
        lifecycleScope.launch { viewModel.note.collectLatest { note -> note?.let { displayNote(it) } } }
        lifecycleScope.launch { viewModel.isFinished.collectLatest { if (it) finish() } }
        lifecycleScope.launch { viewModel.noteMovedToTrash.collectLatest { moved ->
            if (moved) {
                updateAllWidgets()
                Toast.makeText(applicationContext, R.string.note_moved_to_trash_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }}
    }

    // Tüm UI elemanları için listener'ların ayarlandığı merkezi fonksiyon.
    private fun setupListeners() {
        binding.btnShowHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.edit_history_dialog_title))
                .setMessage(binding.tvEditHistory.text)
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
        }

        binding.btnBold.setOnClickListener { toggleStyle(Typeface.BOLD) }
        binding.btnItalic.setOnClickListener { toggleStyle(Typeface.ITALIC) }
        binding.btnStrikethrough.setOnClickListener { toggleStyle(-1) }

        binding.etNoteInput.setOnSelectionChangedListener { _, _ ->
            updateFormattingButtonsState()
        }

        binding.btnSaveNote.setOnClickListener { performSave() }
        binding.btnDeleteNote.setOnClickListener { showDeleteConfirmationDialog() }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }

        binding.ivImagePreview.setOnClickListener {
            imageUri?.let { uri ->
                val intent = Intent(this, PhotoViewActivity::class.java).apply {
                    // PhotoViewActivity'e dosya yolu yerine URI'yi string olarak gönderiyoruz.
                    putExtra("IMAGE_URI", uri.toString())
                }
                startActivity(intent)
            }
        }

        binding.btnAddPhoto.setOnClickListener { takePicture() }
        binding.btnRecordAudio.setOnClickListener { toggleRecording() }
        binding.btnVoiceNote.setOnClickListener { toggleSpeechToText() }
    }

    // Notta değişiklik yapılıp yapılmadığını kontrol eden fonksiyon.
    private fun isNoteModified(): Boolean {
        if (initialNoteState == null) return true // Yeni not ise her zaman değiştirilmiş sayılır.
        val currentState = NoteState(
            title = binding.etNoteTitle.text.toString(),
            contentHtml = Html.toHtml(binding.etNoteInput.text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE),
            checklist = checklistAdapter.items,
            color = selectedColor,
            imageUriString = this.imageUri?.toString(),
            audioPath = this.audioPath
        )
        // Mevcut durum ile başlangıç durumunu karşılaştırıyoruz.
        return currentState != initialNoteState
    }

    // Notu kaydetme işlemini yürüten ana fonksiyon.
    private fun performSave() {
        val titleText = binding.etNoteTitle.text.toString().trim()
        val noteContentText = binding.etNoteInput.text
        val isContentEmpty = titleText.isBlank() && noteContentText.isNullOrBlank() && checklistItems.all { it.text.isBlank() } && imageUri == null && audioPath == null

        // Yeni ve boş bir not kaydedilmeye çalışılıyorsa, işlemi iptal edip çık.
        if (currentNoteId == null && isContentEmpty) {
            finish()
            return
        }

        // Eğer notta hiçbir değişiklik yapılmadıysa, kaydetme işlemi yapmadan çık.
        if (!isNoteModified()) {
            finish()
            return
        }

        setResult(Activity.RESULT_OK)

        val noteTextHtml = if (noteContentText != null && !noteContentText.toString().isBlank()) {
            Html.toHtml(noteContentText, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        } else ""

        var permanentImageUriString: String? = null
        imageUri?.let { uri ->
            // Eğer URI, kameradan yeni çekilmiş geçici bir dosyaya aitse, onu kalıcı hale getiriyoruz.
            if (uri.toString().contains(ImageManager.TEMP_IMAGE_PREFIX)) {
                permanentImageUriString = ImageManager.makeImagePermanent(this, uri)?.toString()
                if(permanentImageUriString == null){
                    Toast.makeText(this, "Resim kaydedilirken bir hata oluştu.", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Eğer URI zaten kalıcı bir dosyaya aitse (veritabanından geldiyse), ona dokunmuyoruz.
                permanentImageUriString = uri.toString()
            }
        }

        viewModel.saveOrUpdateNote(
            currentNoteId = currentNoteId,
            title = titleText,
            contentHtml = noteTextHtml,
            checklistItems = checklistItems,
            color = selectedColor,
            audioPath = audioPath,
            imagePath = permanentImageUriString, // Veritabanına URI'yi string olarak kaydediyoruz.
            isFromWidget = isFromWidget
        )
        updateAllWidgets()
    }

    // Kamera ile resim çekme sürecini başlatan fonksiyon.
    private fun takePicture() {
        // Eğer zaten bir resim varsa, kullanıcıya üzerine yazmak isteyip istemediğini soruyoruz.
        if (imageUri != null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.overwrite_photo_title))
                .setMessage(getString(R.string.overwrite_photo_message))
                .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> proceedWithTakePicture() }
                .setNegativeButton(getString(R.string.dialog_no), null)
                .show()
        } else {
            proceedWithTakePicture()
        }
    }

    private fun proceedWithTakePicture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // ImageManager'dan geçici bir URI alıp kamera launcher'ını başlatıyoruz.
            val tempUri = ImageManager.createTempImageUri(this)
            this.tempPhotoUri = tempUri
            takePictureLauncher.launch(tempUri)
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Veritabanından gelen notu ekranda görüntüleyen fonksiyon.
    private fun displayNote(note: Note?) {
        if (note == null) {
            binding.etNoteTitle.text?.clear()
            binding.etNoteInput.text?.clear()
            displayEditHistory(null)
            val oldSize = checklistItems.size
            checklistItems.clear()
            if(oldSize > 0) checklistAdapter.notifyItemRangeRemoved(0, oldSize)
            binding.llAudioPlayer.visibility = View.GONE
            audioPath = null
            binding.ivImagePreview.visibility = View.GONE
            imageUri = null
            selectedColor = "#FFECEFF1"
            updateColorSelection(binding.colorDefault)
            updateWindowBackground()
            initialNoteState = NoteState(title = "", contentHtml = "", checklist = emptyList(), color = selectedColor, imageUriString = null, audioPath = null)
            return
        }

        binding.etNoteTitle.setText(note.title)
        displayEditHistory(note)
        try {
            val content = gson.fromJson(note.content, NoteContent::class.java)
            binding.etNoteInput.setText(Html.fromHtml(content.text, Html.FROM_HTML_MODE_LEGACY))

            val oldSize = checklistItems.size
            checklistItems.clear()
            if (oldSize > 0) checklistAdapter.notifyItemRangeRemoved(0, oldSize)
            checklistItems.addAll(content.checklist)
            if(checklistItems.isNotEmpty()) checklistAdapter.notifyItemRangeInserted(0, checklistItems.size)

            if (content.audioFilePath != null) {
                audioPath = content.audioFilePath
                binding.llAudioPlayer.visibility = View.VISIBLE
                binding.tvAudioTitle.text = note.title.ifBlank { getString(R.string.voice_recording_title) }
                prepareMediaPlayer()
            } else {
                binding.llAudioPlayer.visibility = View.GONE
                audioPath = null
            }

            // Geriye dönük uyumluluk: Hem eski dosya yolları hem de yeni URI'ler burada çalışır.
            if (content.imagePath != null) {
                this.imageUri = Uri.parse(content.imagePath) // String'i URI'ye çeviriyoruz.
                binding.ivImagePreview.visibility = View.VISIBLE
                // Coil, hem "file://" hem de "content://" URI'lerini anlar.
                binding.ivImagePreview.load(this.imageUri) {
                    crossfade(true)
                    placeholder(R.drawable.ic_image_24)
                    error(R.drawable.ic_image_24)
                }
            } else {
                this.imageUri = null
                binding.ivImagePreview.visibility = View.GONE
            }

            initialNoteState = NoteState(
                title = note.title,
                contentHtml = content.text,
                checklist = content.checklist.map { it.copy() },
                color = note.color,
                imageUriString = content.imagePath,
                audioPath = content.audioFilePath
            )

        } catch (_: JsonSyntaxException) {
            // Eski formatlı, sadece metin içeren notlar için hata yönetimi.
            binding.etNoteInput.setText(Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY))
            val oldSize = checklistItems.size
            checklistItems.clear()
            if (oldSize > 0) checklistAdapter.notifyItemRangeRemoved(0, oldSize)
            binding.llAudioPlayer.visibility = View.GONE
            audioPath = null
            binding.ivImagePreview.visibility = View.GONE
            this.imageUri = null
            initialNoteState = NoteState(title = note.title, contentHtml = note.content, checklist = emptyList(), color = note.color, imageUriString = null, audioPath = null)
        }

        selectedColor = note.color
        updateWindowBackground()
        val colorInt = try { note.color.toColorInt() } catch (_: Exception) { Color.WHITE }
        val viewToSelect = colorPickers.getOrNull(
            when (colorInt) {
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_yellow) -> 1
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_blue) -> 2
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_green) -> 3
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_pink) -> 4
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_purple) -> 5
                ContextCompat.getColor(this@NoteActivity, R.color.note_color_orange) -> 6
                else -> 0
            }
        )
        updateColorSelection(viewToSelect)
        updateFormattingButtonsState()
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_note_confirmation_title))
            .setMessage(getString(R.string.delete_note_to_trash_confirmation_message))
            .setPositiveButton(getString(R.string.dialog_move_to_trash)) { _, _ -> viewModel.moveNoteToTrash(currentNoteId) }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // Geri kalan tüm diğer metodlar (ses kaydı, metin formatlama, checklist vb.)
    // bu URI değişikliğinden etkilenmeden aynı şekilde çalışır.
    // Metodların geri kalanını eksiksiz olarak ekliyorum:

    override fun onDestroy() {
        super.onDestroy()
        restartHandler.removeCallbacksAndMessages(null)
        if(::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        releaseMediaPlayer()
        if (isRecording) {
            stopRecording()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        isFromWidget = intent.getBooleanExtra("FROM_WIDGET", false)
        currentNoteId = intent.getIntExtra("NOTE_ID", 0).takeIf { it != 0 }

        if (currentNoteId != null) {
            binding.btnDeleteNote.visibility = View.VISIBLE
            viewModel.loadNote(currentNoteId!!)
        } else {
            currentNoteId = null
            binding.btnDeleteNote.visibility = View.GONE
            displayNote(null)
        }
        binding.btnShowHistory.visibility = if (currentNoteId != null) View.VISIBLE else View.GONE
    }

    private fun setupChecklist() {
        checklistAdapter = ChecklistItemAdapter(checklistItems)
        binding.rvChecklist.adapter = checklistAdapter
        binding.rvChecklist.layoutManager = LinearLayoutManager(this)
        binding.btnAddChecklistItem.setOnClickListener { checklistAdapter.addItem() }
    }

    private fun setupColorPickers() {
        colorPickers = listOf(
            binding.colorDefault, binding.colorYellow, binding.colorBlue,
            binding.colorGreen, binding.colorPink, binding.colorPurple, binding.colorOrange
        )
        val listeners = mapOf(
            binding.colorDefault to R.color.note_color_default,
            binding.colorYellow to R.color.note_color_yellow,
            binding.colorBlue to R.color.note_color_blue,
            binding.colorGreen to R.color.note_color_green,
            binding.colorPink to R.color.note_color_pink,
            binding.colorPurple to R.color.note_color_purple,
            binding.colorOrange to R.color.note_color_orange
        )
        listeners.forEach { (view, colorResId) -> view.setOnClickListener { onColorSelected(it, colorResId) } }
    }

    private fun onColorSelected(view: View, colorResId: Int) {
        selectedColor = String.format("#%08X", ContextCompat.getColor(this, colorResId))
        updateColorSelection(view)
        updateWindowBackground()
    }

    private fun updateColorSelection(selectedView: View?) {
        colorPickers.forEach { it.isSelected = (it == selectedView) }
    }

    private fun getContrastingTextColor(backgroundColor: String): Int {
        return try {
            val colorInt = backgroundColor.toColorInt()
            if ((0.299 * Color.red(colorInt) + 0.587 * Color.green(colorInt) + 0.114 * Color.blue(colorInt)) / 255 > 0.5)
                ContextCompat.getColor(this, R.color.black)
            else
                ContextCompat.getColor(this, R.color.white)
        } catch (_: IllegalArgumentException) {
            ContextCompat.getColor(this, R.color.black)
        }
    }

    private fun updateWindowBackground() {
        try {
            val color = selectedColor.toColorInt()
            window.setBackgroundDrawable(color.toDrawable())
            binding.root.setBackgroundColor(color)
        } catch (_: IllegalArgumentException) {
            val defaultColor = Color.WHITE
            window.setBackgroundDrawable(defaultColor.toDrawable())
            binding.root.setBackgroundColor(defaultColor)
        }
        val textColor = getContrastingTextColor(selectedColor)
        checklistAdapter.updateColors(textColor, textColor)
        if (checklistAdapter.itemCount > 0) {
            checklistAdapter.notifyItemRangeChanged(0, checklistAdapter.itemCount)
        }
        binding.etNoteTitle.setTextColor(textColor)
        binding.etNoteInput.setTextColor(textColor)
    }

    private fun updateAllWidgets() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoteWidgetProvider::class.java)
        appWidgetManager.getAppWidgetIds(componentName).forEach { appWidgetId ->
            NoteWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
        }
    }

    private fun displayEditHistory(note: Note?) {
        if (note == null) {
            binding.tvEditHistory.text = ""
            return
        }
        val historyBuilder = StringBuilder(getString(R.string.creation_date_label, formatDate(note.createdAt, "dd/MM/yyyy HH:mm:ss")))
        if (note.modifiedAt.isNotEmpty()) {
            historyBuilder.append("\n\n${getString(R.string.edit_history_title)}")
            note.modifiedAt.forEach { timestamp ->
                historyBuilder.append("\n- ${formatDate(timestamp, "dd/MM/yyyy HH:mm:ss")}")
            }
        }
        binding.tvEditHistory.text = historyBuilder.toString()
    }

    private fun formatDate(timestamp: Long, format: String): String =
        SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))

    // Diğer tüm fonksiyonlar... (toggleRecording, startRecording, vs.)
    // Bu fonksiyonlar içerisinde bir değişiklik gerekmediği için olduğu gibi bırakılabilir.
    // Eksiksiz olması adına buraya da ekliyorum:

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        if (audioPath != null && File(audioPath!!).exists()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.overwrite_audio_title))
                .setMessage(getString(R.string.overwrite_audio_message))
                .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> proceedWithRecording() }
                .setNegativeButton(getString(R.string.dialog_no), null)
                .show()
        } else {
            proceedWithRecording()
        }
    }

    private fun proceedWithRecording() {
        try {
            val audioFile = createAudioFile()
            audioPath = audioFile.absolutePath

            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(320000)
                setAudioSamplingRate(44100)
                setOutputFile(audioPath)
                prepare()
                start()
            }
            isRecording = true
            binding.btnRecordAudio.setIconResource(R.drawable.ic_stop_24)
            Toast.makeText(this, "Kayıt başladı...", Toast.LENGTH_SHORT).show()
        } catch (_: IOException) {
            Toast.makeText(this, "Kayıt başlatılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        mediaRecorder?.stop()
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        binding.btnRecordAudio.setIconResource(R.drawable.ic_mic)
        Toast.makeText(this, "Kayıt tamamlandı.", Toast.LENGTH_SHORT).show()

        binding.llAudioPlayer.visibility = View.VISIBLE
        val noteTitle = binding.etNoteTitle.text.toString()
        if(noteTitle.isBlank()){
            val titleWithTimestamp = "${getString(R.string.voice_recording_title)} - ${formatDate(System.currentTimeMillis(), "dd/MM/yyyy HH:mm")}"
            binding.etNoteTitle.setText(titleWithTimestamp)
            binding.tvAudioTitle.text = titleWithTimestamp
        } else {
            binding.tvAudioTitle.text = noteTitle
        }
        prepareMediaPlayer()
    }

    private fun createAudioFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir("AudioNotes")
        storageDir?.mkdirs()
        return File.createTempFile("AUDIO_${timeStamp}_", ".mp3", storageDir)
    }

    private fun setupVoiceToText() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.btnVoiceNote.visibility = View.GONE
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { utteranceStartPosition = recognizedTextBuilder.length }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (partialText.isNotBlank()) {
                    recognizedTextBuilder.setLength(utteranceStartPosition)
                    recognizedTextBuilder.append(partialText)
                    binding.etNoteInput.setText(recognizedTextBuilder.toString())
                    binding.etNoteInput.setSelection(binding.etNoteInput.length())
                }
            }

            override fun onResults(results: Bundle?) {
                val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                recognizedTextBuilder.setLength(utteranceStartPosition)
                recognizedTextBuilder.append(finalText)
                if (finalText.isNotBlank()) {
                    recognizedTextBuilder.append(" ")
                }
                binding.etNoteInput.setText(recognizedTextBuilder.toString())
                binding.etNoteInput.setSelection(binding.etNoteInput.length())
            }

            override fun onEndOfSpeech() {
                if (isListening) {
                    restartListeningWithDelay()
                }
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_AUDIO) {
                    stopListening()
                    Toast.makeText(applicationContext, getString(R.string.critical_error_recording_stopped), Toast.LENGTH_SHORT).show()
                } else if (isListening) {
                    restartListeningWithDelay()
                }
            }
        })
    }

    private fun toggleSpeechToText() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestVoiceToTextPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!isListening) {
            startListening()
        } else {
            stopListening()
        }
    }

    private fun startListening() {
        isListening = true
        binding.btnVoiceNote.setIconResource(R.drawable.ic_microphone_red_24)
        Toast.makeText(applicationContext, getString(R.string.speech_listening), Toast.LENGTH_SHORT).show()
        recognizedTextBuilder.clear()
        val currentText = binding.etNoteInput.text.toString()
        recognizedTextBuilder.append(currentText)
        if (currentText.isNotEmpty() && !currentText.endsWith(" ")) {
            recognizedTextBuilder.append(" ")
        }
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        restartHandler.removeCallbacksAndMessages(null)
        speechRecognizer.stopListening()
        binding.btnVoiceNote.setIconResource(R.drawable.ic_microphone_24)
    }

    private fun restartListeningWithDelay() {
        restartHandler.postDelayed({
            if (isListening) {
                try {
                    speechRecognizer.startListening(speechRecognizerIntent)
                } catch (_: Exception) {
                    stopListening()
                }
            }
        }, 100)
    }

    private fun toggleStyle(styleType: Int) {
        val noteInput = binding.etNoteInput
        val spannable = noteInput.text as SpannableStringBuilder
        val start = noteInput.selectionStart
        val end = noteInput.selectionEnd

        val (spanClass, newSpan) = when (styleType) {
            Typeface.BOLD -> StyleSpan::class.java to StyleSpan(Typeface.BOLD)
            Typeface.ITALIC -> StyleSpan::class.java to StyleSpan(Typeface.ITALIC)
            -1 -> StrikethroughSpan::class.java to StrikethroughSpan()
            else -> return
        }

        if (start != end) {
            val existingSpans = spannable.getSpans(start, end, spanClass)
            val styleExists = existingSpans.any {
                (it is StyleSpan && newSpan is StyleSpan && it.style == newSpan.style) || it is StrikethroughSpan
            }

            if (styleExists) {
                existingSpans.forEach {
                    if ((it is StyleSpan && newSpan is StyleSpan && it.style == newSpan.style) || it is StrikethroughSpan) {
                        spannable.removeSpan(it)
                    }
                }
            } else {
                spannable.setSpan(newSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        else {
            val position = noteInput.selectionStart
            val activeSpans = spannable.getSpans(position, position, Any::class.java)

            val activeStyleSpan = activeSpans.find {
                val isMatchingStyle = (it is StyleSpan && newSpan is StyleSpan && it.style == newSpan.style) || it is StrikethroughSpan
                isMatchingStyle && spannable.getSpanFlags(it) == Spanned.SPAN_INCLUSIVE_INCLUSIVE
            }

            if (activeStyleSpan != null) {
                val spanStart = spannable.getSpanStart(activeStyleSpan)
                spannable.removeSpan(activeStyleSpan)
                if (position > spanStart) {
                    spannable.setSpan(newSpan, spanStart, position, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            else {
                spannable.setSpan(newSpan, position, position, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
        updateFormattingButtonsState()
    }

    private fun updateFormattingButtonsState() {
        isUpdatingToggleButtons = true

        val spannable = binding.etNoteInput.text ?: return
        val position = binding.etNoteInput.selectionStart
        val selectionEnd = binding.etNoteInput.selectionEnd

        if (position != selectionEnd) {
            val boldSpans = spannable.getSpans(position, selectionEnd, StyleSpan::class.java)
            binding.btnBold.isChecked = boldSpans.any { it.style == Typeface.BOLD }

            val italicSpans = spannable.getSpans(position, selectionEnd, StyleSpan::class.java)
            binding.btnItalic.isChecked = italicSpans.any { it.style == Typeface.ITALIC }

            val strikeSpans = spannable.getSpans(position, selectionEnd, StrikethroughSpan::class.java)
            binding.btnStrikethrough.isChecked = strikeSpans.isNotEmpty()
        } else {
            val spansAtCursor = spannable.getSpans(position, position, Any::class.java)

            binding.btnBold.isChecked = spansAtCursor.any {
                it is StyleSpan && it.style == Typeface.BOLD && spannable.getSpanFlags(it) == Spanned.SPAN_INCLUSIVE_INCLUSIVE
            }
            binding.btnItalic.isChecked = spansAtCursor.any {
                it is StyleSpan && it.style == Typeface.ITALIC && spannable.getSpanFlags(it) == Spanned.SPAN_INCLUSIVE_INCLUSIVE
            }
            binding.btnStrikethrough.isChecked = spansAtCursor.any {
                it is StrikethroughSpan && spannable.getSpanFlags(it) == Spanned.SPAN_INCLUSIVE_INCLUSIVE
            }
        }

        isUpdatingToggleButtons = false
    }

    private fun prepareMediaPlayer() {
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(audioPath)
                prepareAsync()
                setOnPreparedListener {
                    binding.btnPlayPause.isEnabled = true
                }
                setOnCompletionListener {
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.btnPlayPause.contentDescription = getString(R.string.play)
                }
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.btnPlayPause.contentDescription = getString(R.string.play)
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this@NoteActivity, getString(R.string.audio_file_cannot_be_played), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.btnPlayPause.contentDescription = getString(R.string.play)
            } else {
                it.start()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnPlayPause.contentDescription = getString(R.string.pause)
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}