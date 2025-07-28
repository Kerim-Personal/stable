package com.codenzi.snapnote

import com.google.gson.annotations.SerializedName

/**
 * Yedekleme verilerini bir arada tutan ana veri sınıfı.
 * Bu sınıf, ayarları, notları ve güvenlik kimlik bilgilerini içerir.
 */
data class BackupData(
    @SerializedName("settings")
    val settings: AppSettings,

    @SerializedName("notes")
    val notes: List<Note>,

    @SerializedName("password_hash")
    val passwordHash: String?,

    @SerializedName("salt")
    val salt: String?,

    @SerializedName("security_question")
    val securityQuestion: String?,

    @SerializedName("security_answer_hash")
    val securityAnswerHash: String?,

    @SerializedName("security_salt")
    val securitySalt: String?
)

/**
 * Uygulama ayarlarını içeren veri sınıfı.
 * Tema, renk ve widget arkaplanı gibi kullanıcı tercihlerini saklar.
 */
data class AppSettings(
    @SerializedName("theme_selection")
    val themeSelection: String?,

    @SerializedName("color_selection")
    val colorSelection: String?,

    @SerializedName("widget_background_selection")
    val widgetBackgroundSelection: String?
)