package com.codenzi.snapnote

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ImageManager {

    // HATA DÜZELTME: Değişkenler 'private' yerine 'internal' yapıldı.
    // Bu sayede NoteActivity gibi aynı modüldeki diğer sınıflar bu değişkenlere erişebilir.
    internal const val TEMP_IMAGE_PREFIX = "TEMP_"
    private const val FINAL_IMAGE_PREFIX = "IMG_"
    private const val IMAGE_SUFFIX = ".jpg"

    // Geçici resim dosyaları için kullanılacak klasörü belirler.
    private fun getTempDirectory(context: Context): File {
        // cacheDir, sistem tarafından gerektiğinde temizlenebilen bir dizindir.
        val tempDir = File(context.cacheDir, "temp_images")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    // Kalıcı resim dosyaları için kullanılacak klasörü belirler.
    private fun getPermanentDirectory(context: Context): File {
        // getExternalFilesDir, uygulamanın kaldırılmasıyla silinir ve kullanıcıya aittir.
        val permanentDir = context.getExternalFilesDir("Images")
        if (permanentDir != null && !permanentDir.exists()) {
            permanentDir.mkdirs()
        }
        // Eğer external storage kullanılamıyorsa, internal storage'a döner.
        return permanentDir ?: context.filesDir
    }

    /**
     * Fotoğraf çekimi için geçici bir dosya ve URI'sini oluşturur.
     * @return Geçici dosya ve FileProvider URI'sini içeren bir Pair.
     */
    fun createTempImageFile(context: Context): Pair<File, android.net.Uri> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempDir = getTempDirectory(context)
        val tempFile = File(tempDir, "$TEMP_IMAGE_PREFIX${timeStamp}$IMAGE_SUFFIX")
        val tempUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", tempFile)
        return Pair(tempFile, tempUri)
    }

    /**
     * Geçici bir resim dosyasını kalıcı hale getirir ve yeni yolunu döndürür.
     * @param tempImagePath Geçici resim dosyasının yolu.
     * @return Kalıcı dosyanın yolu veya işlem başarısız olursa null.
     */
    fun makeImagePermanent(context: Context, tempImagePath: String): String? {
        val tempFile = File(tempImagePath)
        // Sadece bizim oluşturduğumuz geçici dosyaları işleme al.
        if (!tempFile.exists() || !tempFile.name.startsWith(TEMP_IMAGE_PREFIX)) {
            return if (tempFile.exists()) tempImagePath else null
        }

        val permanentDir = getPermanentDirectory(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val permanentFile = File(permanentDir, "$FINAL_IMAGE_PREFIX${timeStamp}$IMAGE_SUFFIX")

        return try {
            tempFile.copyTo(permanentFile, overwrite = true)
            tempFile.delete() // Kopyalama sonrası geçici dosyayı sil.
            permanentFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Uygulama başlangıcında çağrılarak sahipsiz kalmış geçici dosyaları temizler.
     */
    fun cleanUpTemporaryFiles(context: Context) {
        val tempDir = getTempDirectory(context)
        if (tempDir.exists() && tempDir.isDirectory) {
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(TEMP_IMAGE_PREFIX)) {
                    file.delete()
                }
            }
        }
    }
}