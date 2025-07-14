package com.codenzi.snapnote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private var recordingStartTime: Long = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable

    companion object {
        const val ACTION_START_RECORDING = "com.codenzi.acilnot.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.codenzi.acilnot.action.STOP_RECORDING"
        private const val NOTIFICATION_CHANNEL_ID = "AudioRecordingChannel"
        private const val NOTIFICATION_ID = 12345
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecordingAndSave()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (VoiceMemoWidgetProvider.getWidgetState(this) != WidgetState.IDLE) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            audioFile = createAudioFile()
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(192000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder

            recordingStartTime = System.currentTimeMillis()
            startTimer()

            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())

            VoiceMemoWidgetProvider.setWidgetState(this, WidgetState.RECORDING)

        } catch (e: IOException) {
            e.printStackTrace()
            try { cleanup() } catch (ignored: Exception) {}
        }
    }

    private fun stopRecordingAndSave() {
        if (mediaRecorder == null) return
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
            saveAudioNote()
            cleanup()
        }
    }

    private fun cleanup() {
        timerHandler.removeCallbacks(timerRunnable)
        VoiceMemoWidgetProvider.setWidgetState(this, WidgetState.SAVED)

        Handler(Looper.getMainLooper()).postDelayed({
            VoiceMemoWidgetProvider.setWidgetState(this, WidgetState.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2000)
    }

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (mediaRecorder == null) return
                val elapsedMillis = System.currentTimeMillis() - recordingStartTime
                val formattedTime = String.format(Locale.getDefault(), "%02d:%02d",
                    TimeUnit.MILLISECONDS.toMinutes(elapsedMillis),
                    TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) % 60
                )
                updateWidgetTimer(formattedTime)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable)
    }

    private fun updateWidgetTimer(formattedTime: String) {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, VoiceMemoWidgetProvider::class.java)

        val remoteViews = RemoteViews(packageName, R.layout.widget_voice_memo)

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val backgroundDrawableName = sharedPrefs.getString("widget_background_selection", "widget_background")
        val backgroundResId = VoiceMemoWidgetProvider.getBackgroundResource(backgroundDrawableName)
        remoteViews.setInt(R.id.voice_widget_container, "setBackgroundResource", backgroundResId)

        remoteViews.setImageViewResource(R.id.btn_record_voice, R.drawable.ic_stop_24)
        remoteViews.setTextViewText(R.id.tv_widget_status, getString(R.string.tap_to_stop))
        remoteViews.setViewVisibility(R.id.tv_widget_timer, View.VISIBLE)

        remoteViews.setTextViewText(R.id.tv_widget_timer, formattedTime)

        appWidgetManager.updateAppWidget(componentName, remoteViews)
    }

    private fun saveAudioNote() {
        if (audioFile == null || !audioFile!!.exists() || audioFile!!.length() == 0L) return
        val noteDao = NoteDatabase.getDatabase(this).noteDao()
        val title = "${getString(R.string.voice_recording_title)} - ${formatDate(System.currentTimeMillis())}"
        val contentJson = Gson().toJson(NoteContent(text = "", checklist = mutableListOf(), audioFilePath = audioFile?.absolutePath))
        CoroutineScope(Dispatchers.IO).launch {
            noteDao.insert(Note(title = title, content = contentJson, createdAt = System.currentTimeMillis()))
        }
    }

    private fun createAudioFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir("AudioNotes")
        storageDir?.mkdirs()
        return File.createTempFile("AUDIO_${timeStamp}_", ".mp3", storageDir)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AudioRecordingService::class.java).apply { action = ACTION_STOP_RECORDING }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_in_progress))
            .setSmallIcon(R.drawable.ic_microphone_24)
            .addAction(R.drawable.ic_stop_24, getString(R.string.stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.audio_recording_service_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))

    override fun onBind(intent: Intent?): IBinder? = null
}