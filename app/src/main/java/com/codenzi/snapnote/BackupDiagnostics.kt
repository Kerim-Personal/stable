package com.codenzi.snapnote

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yedekleme ve geri yükleme sisteminin durumunu kontrol eden tanı yardımcı sınıfı.
 * Bu sınıf sorun giderme için detaylı bilgi toplar.
 */
object BackupDiagnostics {
    
    private const val TAG = "BackupDiagnostics"
    
    /**
     * Kapsamlı sistem tanısı çalıştırır ve sonuçları döndürür.
     */
    suspend fun runComprehensiveDiagnostics(context: Context): DiagnosticReport = withContext(Dispatchers.IO) {
        val report = DiagnosticReport()
        
        try {
            // Temel sistem bilgileri
            report.deviceInfo = collectDeviceInfo()
            
            // Ağ bağlantısı kontrolü
            report.networkStatus = checkNetworkConnectivity(context)
            
            // Google Play Hizmetleri kontrolü
            report.googlePlayServicesStatus = checkGooglePlayServices(context)
            
            // İzinler kontrolü
            report.permissionsStatus = checkPermissions(context)
            
            // Uygulama ayarları kontrolü
            report.appSettings = checkAppSettings(context)
            
            // Veritabanı durumu kontrolü
            report.databaseStatus = checkDatabaseStatus(context)
            
            // OAuth konfigürasyonu kontrolü
            report.oauthStatus = checkOAuthConfiguration(context)
            
            // Dosya sistemi kontrolü
            report.fileSystemStatus = checkFileSystemAccess(context)
            
            Log.d(TAG, "Tanı raporu tamamlandı")
            
        } catch (e: Exception) {
            Log.e(TAG, "Tanı çalıştırılırken hata oluştu", e)
            report.errors.add("Tanı hatası: ${e.message}")
        }
        
        report
    }
    
    private fun collectDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = "1.2.2", // From build.gradle.kts
            buildType = "Unknown" // BuildConfig may not be accessible in this context
        )
    }
    
    private fun checkNetworkConnectivity(context: Context): NetworkStatus {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        
        if (network == null) {
            return NetworkStatus(isConnected = false, connectionType = "None", details = "Aktif ağ bağlantısı yok")
        }
        
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            return NetworkStatus(isConnected = false, connectionType = "Unknown", details = "Ağ özellikleri alınamadı")
        }
        
        val connectionType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        
        return NetworkStatus(
            isConnected = hasInternet && isValidated,
            connectionType = connectionType,
            details = "Internet: $hasInternet, Doğrulanmış: $isValidated"
        )
    }
    
    private fun checkGooglePlayServices(context: Context): GooglePlayServicesStatus {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        
        return GooglePlayServicesStatus(
            isAvailable = resultCode == ConnectionResult.SUCCESS,
            resultCode = resultCode,
            errorMessage = if (resultCode != ConnectionResult.SUCCESS) {
                googleApiAvailability.getErrorString(resultCode)
            } else null
        )
    }
    
    private fun checkPermissions(context: Context): PermissionsStatus {
        val requiredPermissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        )
        
        val permissionStatuses = requiredPermissions.associate { permission ->
            permission to (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        
        return PermissionsStatus(
            allGranted = permissionStatuses.values.all { it },
            permissionDetails = permissionStatuses
        )
    }
    
    private suspend fun checkAppSettings(context: Context): AppSettingsStatus = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPasswordSet = try {
            PasswordManager.isPasswordSet()
        } catch (e: Exception) {
            Log.e(TAG, "PasswordManager kontrolü başarısız", e)
            false
        }
        
        AppSettingsStatus(
            isPasswordEnabled = isPasswordSet,
            themeSelection = prefs.getString("theme_selection", "system_default"),
            colorSelection = prefs.getString("color_selection", "rose"),
            widgetBackgroundSelection = prefs.getString("widget_background_selection", "widget_background")
        )
    }
    
    private suspend fun checkDatabaseStatus(context: Context): DatabaseStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            // Not sayısını almayı dene
            // Bu gerçek implementasyonda NoteDao injection gerektirir, basit kontrol için dosya varlığını kontrol edelim
            val dbFile = context.getDatabasePath("note_database")
            DatabaseStatus(
                isAccessible = dbFile.exists(),
                databasePath = dbFile.absolutePath,
                databaseSize = if (dbFile.exists()) dbFile.length() else 0L,
                details = if (dbFile.exists()) "Veritabanı dosyası mevcut" else "Veritabanı dosyası bulunamadı"
            )
        } catch (e: Exception) {
            DatabaseStatus(
                isAccessible = false,
                databasePath = "Unknown",
                databaseSize = 0L,
                details = "Veritabanı kontrolü başarısız: ${e.message}"
            )
        }
    }
    
    private fun checkOAuthConfiguration(context: Context): OAuthStatus {
        return try {
            val clientId = context.getString(R.string.your_web_client_id)
            val isValidFormat = clientId.isNotEmpty() && 
                               clientId.contains(".apps.googleusercontent.com") &&
                               !clientId.contains("your_web_client_id")
            
            OAuthStatus(
                isConfigured = isValidFormat,
                clientId = if (isValidFormat) clientId else "Geçersiz",
                details = when {
                    clientId.isEmpty() -> "Client ID boş"
                    clientId.contains("your_web_client_id") -> "Client ID placeholder değeri"
                    !clientId.contains(".apps.googleusercontent.com") -> "Client ID formatı geçersiz"
                    else -> "Client ID geçerli görünüyor"
                }
            )
        } catch (e: Exception) {
            OAuthStatus(
                isConfigured = false,
                clientId = "Error",
                details = "Client ID alınamadı: ${e.message}"
            )
        }
    }
    
    private fun checkFileSystemAccess(context: Context): FileSystemStatus {
        val issues = mutableListOf<String>()
        var isHealthy = true
        
        try {
            // External files directory kontrolü
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir == null) {
                issues.add("External files directory erişilemiyor")
                isHealthy = false
            } else {
                val canWrite = externalFilesDir.canWrite()
                if (!canWrite) {
                    issues.add("External files directory yazma izni yok")
                    isHealthy = false
                }
            }
            
            // Cache directory kontrolü
            val cacheDir = context.cacheDir
            if (!cacheDir.canWrite()) {
                issues.add("Cache directory yazma izni yok")
                isHealthy = false
            }
            
            // Geçici dosya oluşturma testi
            try {
                val tempFile = kotlin.io.path.createTempFile(directory = cacheDir.toPath(), prefix = "test_", suffix = ".tmp").toFile()
                tempFile.delete()
            } catch (e: Exception) {
                issues.add("Geçici dosya oluşturulamıyor: ${e.message}")
                isHealthy = false
            }
            
        } catch (e: Exception) {
            issues.add("Dosya sistemi kontrolü başarısız: ${e.message}")
            isHealthy = false
        }
        
        return FileSystemStatus(
            isHealthy = isHealthy,
            issues = issues
        )
    }
    
    /**
     * Tanı raporunu okunabilir bir string formatına dönüştürür
     */
    fun formatDiagnosticReport(report: DiagnosticReport): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        return buildString {
            appendLine("=== SnapNote Backup/Restore Tanı Raporu ===")
            appendLine("Tarih: $timestamp")
            appendLine()
            
            appendLine("📱 CİHAZ BİLGİLERİ:")
            appendLine("Model: ${report.deviceInfo?.deviceModel}")
            appendLine("Android: ${report.deviceInfo?.androidVersion}")
            appendLine("Uygulama: ${report.deviceInfo?.appVersion} (${report.deviceInfo?.buildType})")
            appendLine()
            
            appendLine("🌐 AĞ DURUMU:")
            report.networkStatus?.let { network ->
                appendLine("Bağlantı: ${if (network.isConnected) "✅ Aktif" else "❌ Yok"}")
                appendLine("Tür: ${network.connectionType}")
                appendLine("Detay: ${network.details}")
            }
            appendLine()
            
            appendLine("🔧 GOOGLE PLAY HİZMETLERİ:")
            report.googlePlayServicesStatus?.let { gps ->
                appendLine("Durum: ${if (gps.isAvailable) "✅ Mevcut" else "❌ Sorunlu"}")
                gps.errorMessage?.let { appendLine("Hata: $it") }
            }
            appendLine()
            
            appendLine("🔐 İZİNLER:")
            report.permissionsStatus?.let { perms ->
                appendLine("Durum: ${if (perms.allGranted) "✅ Tümü verildi" else "❌ Eksik izin var"}")
                perms.permissionDetails.forEach { (perm, granted) ->
                    appendLine("${if (granted) "✅" else "❌"} ${perm.substringAfterLast(".")}")
                }
            }
            appendLine()
            
            appendLine("⚙️ UYGULAMA AYARLARI:")
            report.appSettings?.let { settings ->
                appendLine("Şifre: ${if (settings.isPasswordEnabled) "✅ Etkin" else "❌ Yok"}")
                appendLine("Tema: ${settings.themeSelection}")
                appendLine("Renk: ${settings.colorSelection}")
            }
            appendLine()
            
            appendLine("🗃️ VERİTABANI:")
            report.databaseStatus?.let { db ->
                appendLine("Erişim: ${if (db.isAccessible) "✅ OK" else "❌ Sorunlu"}")
                appendLine("Boyut: ${db.databaseSize} byte")
                appendLine("Detay: ${db.details}")
            }
            appendLine()
            
            appendLine("🔑 OAUTH YAPILANDIRMASI:")
            report.oauthStatus?.let { oauth ->
                appendLine("Durum: ${if (oauth.isConfigured) "✅ Yapılandırılmış" else "❌ Sorunlu"}")
                appendLine("Client ID: ${oauth.clientId}")
                appendLine("Detay: ${oauth.details}")
            }
            appendLine()
            
            appendLine("📁 DOSYA SİSTEMİ:")
            report.fileSystemStatus?.let { fs ->
                appendLine("Durum: ${if (fs.isHealthy) "✅ Sağlıklı" else "❌ Sorunlu"}")
                fs.issues.forEach { issue ->
                    appendLine("⚠️ $issue")
                }
            }
            appendLine()
            
            if (report.errors.isNotEmpty()) {
                appendLine("❌ HATALAR:")
                report.errors.forEach { error ->
                    appendLine("• $error")
                }
                appendLine()
            }
            
            appendLine("=== Rapor Sonu ===")
        }
    }
}

// Veri sınıfları
data class DiagnosticReport(
    var deviceInfo: DeviceInfo? = null,
    var networkStatus: NetworkStatus? = null,
    var googlePlayServicesStatus: GooglePlayServicesStatus? = null,
    var permissionsStatus: PermissionsStatus? = null,
    var appSettings: AppSettingsStatus? = null,
    var databaseStatus: DatabaseStatus? = null,
    var oauthStatus: OAuthStatus? = null,
    var fileSystemStatus: FileSystemStatus? = null,
    val errors: MutableList<String> = mutableListOf()
)

data class DeviceInfo(
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val buildType: String
)

data class NetworkStatus(
    val isConnected: Boolean,
    val connectionType: String,
    val details: String
)

data class GooglePlayServicesStatus(
    val isAvailable: Boolean,
    val resultCode: Int,
    val errorMessage: String?
)

data class PermissionsStatus(
    val allGranted: Boolean,
    val permissionDetails: Map<String, Boolean>
)

data class AppSettingsStatus(
    val isPasswordEnabled: Boolean,
    val themeSelection: String?,
    val colorSelection: String?,
    val widgetBackgroundSelection: String?
)

data class DatabaseStatus(
    val isAccessible: Boolean,
    val databasePath: String,
    val databaseSize: Long,
    val details: String
)

data class OAuthStatus(
    val isConfigured: Boolean,
    val clientId: String,
    val details: String
)

data class FileSystemStatus(
    val isHealthy: Boolean,
    val issues: List<String>
)