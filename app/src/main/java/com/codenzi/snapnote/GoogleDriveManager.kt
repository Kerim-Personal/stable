package com.codenzi.snapnote

import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Google Drive işlemlerinin sonucunu temsil eden kapalı (sealed) bir sınıf.
 * Bu yapı, API çağrılarının başarılı veya başarısız olma durumlarını net bir şekilde yönetmeyi sağlar.
 */
sealed class DriveResult<out T> {
    /**
     * İşlemin başarıyla tamamlandığını ve veri içerdiğini belirtir.
     * @property data İşlem sonucu elde edilen veri.
     */
    data class Success<T>(val data: T) : DriveResult<T>()

    /**
     * İşlemin bir hata ile sonuçlandığını belirtir.
     * @property exception Oluşan hata.
     */
    data class Error(val exception: Exception) : DriveResult<Nothing>()
}

/**
 * Google Drive API'si ile etkileşim kurarak yedekleme ve geri yükleme işlemlerini yönetir.
 * Tüm işlemler, ağ ve dosya G/Ç operasyonları için optimize edilmiş ve IO thread'inde çalışacak şekilde tasarlanmıştır.
 *
 * @param credential Google hesabına erişim için yetkilendirme bilgileri.
 */
class GoogleDriveManager(private val credential: GoogleAccountCredential) {

    private val drive: Drive by lazy {
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("SnapNote").build()
    }

    private companion object {
        const val TAG = "GoogleDriveManager"
        const val APPDATA_FOLDER = "appDataFolder"
        const val JSON_MIME_TYPE = "application/json"
        const val FILE_FIELDS = "id, name, modifiedTime"
        const val BACKUP_FILE_NAME = "snapnote_backup.json"
    }

    /**
     * Belirtilen isimdeki bir dosyayı `appDataFolder` içinde arar.
     * @param fileName Aranacak dosyanın adı.
     * @return Dosya bulunduysa [DriveResult.Success] içinde [File] nesnesi, aksi halde [DriveResult.Error] döner.
     */
    private suspend fun findFile(fileName: String): DriveResult<File?> = withContext(Dispatchers.IO) {
        try {
            val files = drive.files().list()
                .setSpaces(APPDATA_FOLDER)
                .setQ("name = '$fileName'")
                .setFields("files(id, name)")
                .execute()
                .files
            DriveResult.Success(files.firstOrNull())
        } catch (e: IOException) {
            Log.e(TAG, "Dosya arama başarısız: $fileName", e)
            DriveResult.Error(e)
        }
    }

    /**
     * JSON formatındaki yedekleme verisini Google Drive'a yükler.
     * Eğer aynı isimde bir dosya varsa günceller, yoksa yeni bir tane oluşturur.
     *
     * @param fileName Drive'a kaydedilecek dosyanın adı.
     * @param content Yüklenecek JSON içeriği.
     * @return Başarılı olursa [DriveResult.Success] içinde dosya ID'si, aksi halde [DriveResult.Error] döner.
     */
    suspend fun uploadJsonBackup(fileName: String, content: String): DriveResult<String> = withContext(Dispatchers.IO) {
        try {
            val metadata = File().apply { name = fileName }
            val contentStream = ByteArrayContent(JSON_MIME_TYPE, content.toByteArray())

            val existingFileResult = findFile(fileName)
            val fileId = if (existingFileResult is DriveResult.Success && existingFileResult.data != null) {
                // Dosya var, güncelle
                drive.files().update(existingFileResult.data.id, metadata, contentStream).execute().id
            } else {
                // Dosya yok, oluştur
                metadata.parents = listOf(APPDATA_FOLDER)
                drive.files().create(metadata, contentStream).setFields("id").execute().id
            }
            DriveResult.Success(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "JSON yedekleme yüklemesi başarısız oldu.", e)
            DriveResult.Error(e)
        }
    }

    /**
     * Belirtilen dosya ID'sine sahip JSON yedek dosyasını Drive'dan indirir.
     *
     * @param fileId İndirilecek dosyanın ID'si.
     * @return Başarılı olursa [DriveResult.Success] içinde dosya içeriği (String), aksi halde [DriveResult.Error] döner.
     */
    suspend fun downloadJsonBackup(fileId: String): DriveResult<String> = withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            DriveResult.Success(outputStream.toString("UTF-8"))
        } catch (e: IOException) {
            Log.e(TAG, "JSON yedek indirme başarısız oldu.", e)
            DriveResult.Error(e)
        }
    }

    /**
     * `appDataFolder` içindeki tüm yedekleme dosyalarını, en yeniden eskiye sıralanmış şekilde listeler.
     *
     * @return Başarılı olursa [DriveResult.Success] içinde [File] listesi, aksi halde [DriveResult.Error] döner.
     */
    suspend fun getBackupFiles(): DriveResult<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = drive.files().list()
                .setSpaces(APPDATA_FOLDER)
                .setFields("files($FILE_FIELDS)")
                .setQ("name = '$BACKUP_FILE_NAME'")
                .setOrderBy("modifiedTime desc")
                .execute()
                .files
            DriveResult.Success(files ?: emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Yedek dosyaları alınamadı.", e)
            DriveResult.Error(e)
        }
    }

    /**
     * Cihazdaki bir medya dosyasını (resim, ses vb.) Google Drive'a yükler.
     *
     * @param localFile Cihazda bulunan dosya.
     * @param mimeType Yüklenecek dosyanın MIME türü (örn: "image/jpeg").
     * @return Başarılı olursa [DriveResult.Success] içinde Drive'daki dosyanın ID'si, aksi halde [DriveResult.Error] döner.
     */
    suspend fun uploadMediaFile(localFile: java.io.File, mimeType: String): DriveResult<String> = withContext(Dispatchers.IO) {
        try {
            val metadata = File().apply {
                name = localFile.name
                parents = listOf(APPDATA_FOLDER)
            }
            val mediaContent = FileContent(mimeType, localFile)
            val file = drive.files().create(metadata, mediaContent).setFields("id").execute()
            DriveResult.Success(file.id)
        } catch (e: IOException) {
            Log.e(TAG, "Medya dosyası yüklenemedi: ${localFile.name}", e)
            DriveResult.Error(e)
        }
    }

    /**
     * Drive'daki bir medya dosyasını cihazdaki belirtilen hedefe indirir.
     *
     * @param fileId İndirilecek dosyanın ID'si.
     * @param destinationStream İndirilen verinin yazılacağı `OutputStream`.
     * @return İşlem başarılıysa [DriveResult.Success] içinde `Unit`, aksi halde [DriveResult.Error] döner.
     */
    suspend fun downloadMediaFile(fileId: String, destinationStream: OutputStream): DriveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            drive.files().get(fileId).executeMediaAndDownloadTo(destinationStream)
            DriveResult.Success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Medya dosyası indirilemedi: $fileId", e)
            DriveResult.Error(e)
        }
    }

    /**
     * Drive'daki belirtilen dosyayı ismine göre bulur ve siler.
     *
     * @param fileName Silinecek dosyanın adı.
     * @return İşlem başarılıysa [DriveResult.Success], aksi halde [DriveResult.Error] döner.
     */
    suspend fun deleteFile(fileName: String): DriveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileResult = findFile(fileName)
            when (fileResult) {
                is DriveResult.Success -> {
                    if (fileResult.data != null) {
                        try {
                            drive.files().delete(fileResult.data.id).execute()
                            Log.d(TAG, "Dosya başarıyla silindi: $fileName")
                        } catch (e: GoogleJsonResponseException) {
                            // Dosya zaten yoksa (404), bunu bir başarı olarak kabul et.
                            if (e.statusCode == 404) {
                                Log.w(TAG, "Silinmeye çalışılan dosya zaten mevcut değil: $fileName")
                            } else {
                                Log.e(TAG, "Dosya silme hatası: $fileName", e)
                                throw e
                            }
                        }
                    } else {
                        Log.w(TAG, "Silinecek dosya bulunamadı: $fileName")
                    }
                }
                is DriveResult.Error -> {
                    Log.e(TAG, "Dosya arama sırasında hata oluştu: $fileName", fileResult.exception)
                    return@withContext fileResult
                }
            }
            DriveResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "$fileName dosyası silinemedi.", e)
            DriveResult.Error(e)
        }
    }
}