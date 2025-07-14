package com.codenzi.snapnote

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Uygulamanın tüm verilerini kalıcı olarak silmek için yönetici sınıfı.
 * Bu sınıf, Android'in `clearApplicationUserData` API'sini kullanarak,
 * uygulamanın ilk kurulduğu haline dönmesini garanti eder.
 */
object DataWipeManager {

    /**
     * Uygulamanın tüm kullanıcı verilerini, önbelleğini, veritabanlarını ve dosyalarını siler.
     * Bu işlem geri alınamaz ve işlem sonrası uygulama yeniden başlatılır.
     * @return İşlemin başlatılması başarılı olursa true, aksi takdirde false.
     */
    fun wipeAllData(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.clearApplicationUserData() ?: false
        } catch (e: Exception) {
            Log.e("DataWipeManager", "Failed to clear application user data.", e)
            false
        }
    }
}