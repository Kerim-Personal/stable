package com.codenzi.snapnote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

object PasswordManager {

    private const val PREFS_NAME = "AppSecurityPrefs"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_SALT = "salt"
    private const val KEY_IS_PASSWORD_ENABLED = "is_password_enabled"
    private const val KEY_SECURITY_QUESTION = "security_question"
    private const val KEY_SECURITY_ANSWER_HASH = "security_answer_hash"
    private const val KEY_SECURITY_SALT = "security_salt"


    @Volatile
    private var encryptedPrefsInstance: SharedPreferences? = null

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

    fun setPasswordAndSecurityQuestion(newPassword: String, question: String, answer: String) {
        val passwordSalt = generateSalt()
        val hashedPassword = hashPassword(newPassword, passwordSalt)
        val securitySalt = generateSalt()
        val hashedAnswer = hashPassword(answer, securitySalt)

        getSharedPreferences().edit(commit = true) {
            putString(KEY_PASSWORD_HASH, hashedPassword)
            putString(KEY_SALT, Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            putBoolean(KEY_IS_PASSWORD_ENABLED, true)
            putString(KEY_SECURITY_QUESTION, question)
            putString(KEY_SECURITY_ANSWER_HASH, hashedAnswer)
            putString(KEY_SECURITY_SALT, Base64.encodeToString(securitySalt, Base64.NO_WRAP))
        }
    }

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

    fun checkPassword(enteredPassword: String, saltBase64: String, hash: String): Boolean {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val enteredPasswordHashed = hashPassword(enteredPassword, salt)
        return hash == enteredPasswordHashed
    }

    fun getSecurityQuestion(): String? {
        return getSharedPreferences().getString(KEY_SECURITY_QUESTION, null)
    }

    fun checkSecurityAnswer(enteredAnswer: String): Boolean {
        val prefs = getSharedPreferences()
        val storedHash = prefs.getString(KEY_SECURITY_ANSWER_HASH, null)
        val storedSaltString = prefs.getString(KEY_SECURITY_SALT, null)

        if (storedHash == null || storedSaltString == null) {
            return false
        }

        val storedSalt = Base64.decode(storedSaltString, Base64.NO_WRAP)
        val enteredAnswerHashed = hashPassword(enteredAnswer, storedSalt)
        return storedHash == enteredAnswerHashed
    }

    fun resetPassword(newPassword: String) {
        val salt = generateSalt()
        val hashedPassword = hashPassword(newPassword, salt)
        getSharedPreferences().edit(commit = true) {
            putString(KEY_PASSWORD_HASH, hashedPassword)
            putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        }
    }

    fun isPasswordSet(): Boolean {
        val prefs = getSharedPreferences()
        return prefs.getBoolean(KEY_IS_PASSWORD_ENABLED, false) &&
                prefs.getString(KEY_PASSWORD_HASH, null) != null
    }

    fun disablePassword() {
        getSharedPreferences().edit(commit = true) {
            remove(KEY_PASSWORD_HASH)
            remove(KEY_SALT)
            remove(KEY_SECURITY_QUESTION)
            remove(KEY_SECURITY_ANSWER_HASH)
            remove(KEY_SECURITY_SALT)
            putBoolean(KEY_IS_PASSWORD_ENABLED, false)
        }
    }

    fun getPasswordHash(): String? {
        return getSharedPreferences().getString(KEY_PASSWORD_HASH, null)
    }

    fun getSalt(): String? {
        return getSharedPreferences().getString(KEY_SALT, null)
    }

    // YENİ GETTER'LAR
    fun getSecurityAnswerHash(): String? {
        return getSharedPreferences().getString(KEY_SECURITY_ANSWER_HASH, null)
    }

    fun getSecuritySalt(): String? {
        return getSharedPreferences().getString(KEY_SECURITY_SALT, null)
    }

    fun resetForRestore(context: Context) {
        getSharedPreferences().edit(commit = true) { clear() }
        encryptedPrefsInstance = null
        initialize(context)
    }

    // Geri yükleme için restorePassword yerine bu yeni fonksiyonu kullanacağız.
    fun restoreAllSecurityCredentials(backupData: BackupData) {
        getSharedPreferences().edit(commit = true) {
            // Önce mevcut tüm güvenlik anahtarlarını temizle
            remove(KEY_PASSWORD_HASH)
            remove(KEY_SALT)
            remove(KEY_SECURITY_QUESTION)
            remove(KEY_SECURITY_ANSWER_HASH)
            remove(KEY_SECURITY_SALT)
            putBoolean(KEY_IS_PASSWORD_ENABLED, false)

            // Yedekten gelen verileri yaz
            if (backupData.passwordHash != null && backupData.salt != null) {
                putBoolean(KEY_IS_PASSWORD_ENABLED, true)
                putString(KEY_PASSWORD_HASH, backupData.passwordHash)
                putString(KEY_SALT, backupData.salt)

                if (backupData.securityQuestion != null && backupData.securityAnswerHash != null && backupData.securitySalt != null) {
                    putString(KEY_SECURITY_QUESTION, backupData.securityQuestion)
                    putString(KEY_SECURITY_ANSWER_HASH, backupData.securityAnswerHash)
                    putString(KEY_SECURITY_SALT, backupData.securitySalt)
                }
            }
        }
    }
}