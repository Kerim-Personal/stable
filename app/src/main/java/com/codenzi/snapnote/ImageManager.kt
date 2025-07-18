package com.codenzi.snapnote

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Resim dosyalarını yönetmek için merkezi yardımcı sınıf.
 * Android'in modern depolama standartlarına (Scoped Storage) uyum sağlamak için
 * mutlak dosya yolları yerine güvenli content:// URI'leri kullanır.
 */
object ImageManager {

    internal const val TEMP_IMAGE_PREFIX = "TEMP_"
    private const val FINAL_IMAGE_PREFIX = "IMG_"
    private const val IMAGE_SUFFIX = ".jpg"

    /**
     * Geçici resim dosyaları için uygulamanın önbellek (cache) dizinini döndürür.
     */
    private fun getTempDirectory(context: Context): File {
        val tempDir = File(context.cacheDir, "temp_images")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    /**
     * Kalıcı resim dosyaları için uygulamanın harici depolama alanındaki özel dizinini döndürür.
     */
    private fun getPermanentDirectory(context: Context): File {
        val permanentDir = context.getExternalFilesDir("Images")
        if (permanentDir != null && !permanentDir.exists()) {
            permanentDir.mkdirs()
        }
        return permanentDir ?: context.filesDir // Harici depolama yoksa dahili depolamayı kullan
    }

    /**
     * Fotoğraf çekimi için geçici bir dosya oluşturur ve bu dosyaya erişim için
     * güvenli bir FileProvider URI'si döndürür.
     *
     * @param context Uygulama context'i.
     * @return Geçici resim dosyası için güvenli bir content:// URI'si.
     */
    fun createTempImageUri(context: Context): Uri {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempDir = getTempDirectory(context)
        val tempFile = File(tempDir, "$TEMP_IMAGE_PREFIX${timeStamp}$IMAGE_SUFFIX")
        // Güvenlik ve uyumluluk için FileProvider aracılığıyla URI oluşturuluyor.
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", tempFile)
    }

    /**
     * Geçici bir resim URI'sini kalıcı bir depolama alanına taşır ve yeni, kalıcı
     * dosyaya ait güvenli URI'yi döndürür.
     *
     * @param context Uygulama context'i.
     * @param tempImageUri Kalıcı hale getirilecek geçici resmin URI'si.
     * @return Kalıcı resim dosyası için yeni ve güvenli bir content:// URI'si veya işlem başarısız olursa null.
     */
    fun makeImagePermanent(context: Context, tempImageUri: Uri): Uri? {
        // Gelen URI'den bir InputStream açarak dosya içeriğini okuyoruz.
        // Bu, URI'nin bir dosya yoluna dönüştürülemediği durumlarda bile çalışır.
        return try {
            context.contentResolver.openInputStream(tempImageUri)?.use { inputStream ->
                val permanentDir = getPermanentDirectory(context)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val permanentFile = File(permanentDir, "$FINAL_IMAGE_PREFIX${timeStamp}$IMAGE_SUFFIX")

                // İçeriği yeni kalıcı dosyaya kopyalıyoruz.
                permanentFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }

                // Kopyalama başarılıysa, yeni kalıcı dosya için güvenli bir URI oluşturup döndürüyoruz.
                FileProvider.getUriForFile(context, "${context.packageName}.provider", permanentFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Uygulama başlangıcında veya belirli aralıklarla çağrılarak,
     * önbellekte kalmış sahipsiz geçici resim dosyalarını temizler.
     *
     * @param context Uygulama context'i.
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