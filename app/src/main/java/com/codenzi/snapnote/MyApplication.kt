package com.codenzi.snapnote

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.*
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Sürüm kontrolü ve veri sıfırlama
        checkVersionAndResetData()

        // PasswordManager'ı uygulama başlatılırken YALNIZCA BİR KEZ başlat.
        PasswordManager.initialize(applicationContext)

        // Uygulama her açıldığında, önceki oturumlardan kalmış
        // olabilecek sahipsiz geçici resim dosyalarını temizle.
        ImageManager.cleanUpTemporaryFiles(applicationContext)

        scheduleTrashCleanup()
    }

    private fun checkVersionAndResetData() {
        val prefs = getSharedPreferences("app_version_prefs", Context.MODE_PRIVATE)
        val savedVersionCode = prefs.getInt("version_code", -1)

        try {
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).versionCode
            val databaseFile = getDatabasePath("note_database")

            // Koşul 1: Normal sürüm yükseltme (örneğin, 14 -> 15).
            // Kayıtlı sürüm kodu var ve yeni sürüm kodu daha yüksek.
            val isNormalUpdate = savedVersionCode != -1 && currentVersionCode > savedVersionCode

            // Koşul 2: Sürüm kodu kaydetme özelliği olmayan çok eski bir sürümden (örn. 4) güncelleme.
            // Bu durumda, kaydedilmiş sürüm kodu -1'dir, ANCAK veritabanı dosyası mevcuttur.
            // Bu, bunun temiz bir kurulum değil, bir güncelleme olduğunu gösterir.
            val isUpdateFromLegacy = savedVersionCode == -1 && databaseFile.exists()

            if (isNormalUpdate || isUpdateFromLegacy) {
                // Verileri temizle. Bu işlem uygulamayı otomatik olarak yeniden başlatacaktır.
                DataWipeManager.wipeAllData(this)
                // wipeAllData çağrıldıktan sonra bu noktadan sonraki kodlar çalışmaz,
                // çünkü uygulama kapatılıp temiz bir şekilde yeniden açılır.
                // Yeni sürüm kodu, uygulamanın bu temiz başlangıcında kaydedilecektir.
            } else {
                // Veri silinmediyse (temiz kurulum veya aynı sürümde açılış), mevcut sürüm kodunu kaydet.
                prefs.edit().putInt("version_code", currentVersionCode).apply()
            }

        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun scheduleTrashCleanup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(false)
            .build()

        val repeatingRequest = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "trashCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP,
            repeatingRequest
        )
    }
}