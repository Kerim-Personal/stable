package com.codenzi.snapnote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Uygulama parolalarını güvenli bir şekilde yönetmek için yardımcı sınıf.
 * Bu sınıf, MyApplication'da oluşturulan tek bir EncryptedSharedPreferences örneğini kullanır.
 *
 * İYİLEŞTİRMELER:
 * - Gereksiz 'context' parametreleri metodlardan kaldırıldı. Sınıf, uygulama başlangıcında
 * MyApplication içinde bir kez initialize edilir ve bu instance üzerinden çalışır.
 * - Yedekten geri yükleme için kullanılan 'resetForRestore' metodu, dosyayı silmek yerine
 * SharedPreferences'ı temizleyerek daha güvenli hale getirildi.
 * - 'initialize' metodu, thread-safe olması ve sadece bir kez çalıştırılması için
 * @Volatile ve synchronized blok ile güçlendirildi.
 * - Kodun okunabilirliği ve bakımı, gereksiz parametrelerin kaldırılmasıyla artırıldı.
 */
object PasswordManager {

    private const val PREFS_NAME = "AppSecurityPrefs"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_SALT = "salt"
    private const val KEY_IS_PASSWORD_ENABLED = "is_password_enabled"

    @Volatile
    private var encryptedPrefsInstance: SharedPreferences? = null

    /**
     * PasswordManager'ı uygulama context'i ile başlatır.
     * Bu metodun, herhangi bir işlem yapılmadan önce Application sınıfının onCreate() metodunda
     * yalnızca bir kez çağrılması zorunludur.
     */
    fun initialize(context: Context) {
        if (encryptedPrefsInstance == null) {
            synchronized(this) {
                if (encryptedPrefsInstance == null) {
                    val masterKey = MasterKey.Builder(context.applicationContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    encryptedPrefsInstance = EncryptedSharedPreferences.create(
                        context.applicationContext,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }
            }
        }
    }

    /**
     * EncryptedSharedPreferences örneğini döndürür.
     * @throws IllegalStateException PasswordManager başlatılmamışsa fırlatılır.
     */
    private fun getSharedPreferences(): SharedPreferences {
        return encryptedPrefsInstance ?: throw IllegalStateException(
            "PasswordManager must be initialized in Application.onCreate()"
        )
    }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val combinedBytes = salt + passwordBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(combinedBytes)
        return Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
    }

    private fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }

    /**
     * Yeni bir parola belirler, salt ve hash değerlerini güvenli bir şekilde saklar.
     */
    fun setPassword(newPassword: String) {
        val salt = generateSalt()
        val hashedPassword = hashPassword(newPassword, salt)
        getSharedPreferences().edit(commit = true) {
            putString(KEY_PASSWORD_HASH, hashedPassword)
            putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            putBoolean(KEY_IS_PASSWORD_ENABLED, true)
        }
    }

    /**
     * Girilen parolanın saklanan hash ile eşleşip eşleşmediğini kontrol eder.
     */
    fun checkPassword(enteredPassword: String): Boolean {
        val prefs = getSharedPreferences()
        val storedHash = prefs.getString(KEY_PASSWORD_HASH, null)
        val storedSaltString = prefs.getString(KEY_SALT, null)

        if (storedHash == null || storedSaltString == null) {
            return false
        }

        val storedSalt = Base64.decode(storedSaltString, Base64.NO_WRAP)
        val enteredPasswordHashed = hashPassword(enteredPassword, storedSalt)
        return storedHash == enteredPasswordHashed
    }

    /**
     * Yedekten geri yüklenen bir parola için harici kontrol metodu.
     */
    fun checkPassword(enteredPassword: String, saltBase64: String, hash: String): Boolean {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val enteredPasswordHashed = hashPassword(enteredPassword, salt)
        return hash == enteredPasswordHashed
    }

    /**
     * Uygulamada bir parolanın ayarlı olup olmadığını döndürür.
     */
    fun isPasswordSet(): Boolean {
        val prefs = getSharedPreferences()
        return prefs.getBoolean(KEY_IS_PASSWORD_ENABLED, false) &&
                prefs.getString(KEY_PASSWORD_HASH, null) != null
    }

    /**
     * Ayarlanmış parolayı devre dışı bırakır ve ilgili kayıtları temizler.
     */
    fun disablePassword() {
        getSharedPreferences().edit(commit = true) {
            remove(KEY_PASSWORD_HASH)
            remove(KEY_SALT)
            putBoolean(KEY_IS_PASSWORD_ENABLED, false)
        }
    }

    /**
     * Mevcut parolanın hash'ini döndürür. (Yedekleme için)
     */
    fun getPasswordHash(): String? {
        return getSharedPreferences().getString(KEY_PASSWORD_HASH, null)
    }

    /**
     * Mevcut parolanın salt değerini döndürür. (Yedekleme için)
     */
    fun getSalt(): String? {
        return getSharedPreferences().getString(KEY_SALT, null)
    }

    /**
     * Yedekten geri yükleme işlemi öncesinde mevcut parola bilgilerini temizler.
     * Bu, yeni bir EncryptedSharedPreferences örneği oluşturulmasını tetikler.
     */
    fun resetForRestore(context: Context) {
        // SharedPreferences'ı temizle
        getSharedPreferences().edit(commit = true) { clear() }
        // Instance'ı null yaparak yeniden initialize edilmesini sağla
        encryptedPrefsInstance = null
        initialize(context)
    }

    /**
     * Yedekten alınan hash ve salt değerleri ile parolayı yeniden oluşturur.
     */
    fun restorePassword(hash: String, salt: String) {
        getSharedPreferences().edit(commit = true) {
            putString(KEY_PASSWORD_HASH, hash)
            putString(KEY_SALT, salt)
            putBoolean(KEY_IS_PASSWORD_ENABLED, true)
        }
    }
}