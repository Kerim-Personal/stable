# Bu dosya, getDefaultProguardFile('proguard-android-optimize.txt') tarafından
# sağlanan varsayılan kurallara ek olarak kullanılır.

#------------- Kotlin Coroutines Kuralları -------------
-keep class kotlinx.coroutines.debug.internal.DebugProbesKt { *; }

#------------- Hilt Kuralları -------------
-keep class * implements dagger.hilt.internal.GeneratedComponent { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedEntryPoint { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder { <methods>; }
-keep class dagger.hilt.internal.processedroots.* { <methods>; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class ** {
    @dagger.hilt.android.AndroidEntryPoint <fields>;
    @dagger.hilt.android.HiltAndroidApp <fields>;
    @dagger.hilt.android.lifecycle.HiltViewModel <fields>;
}
-keep class * {
    @dagger.hilt.android.AndroidEntryPoint *;
    @dagger.hilt.android.HiltAndroidApp *;
    @dagger.hilt.android.lifecycle.HiltViewModel *;
}

#------------- Room Kuralları -------------
-keep class androidx.room.** { *; }
-keepclassmembers class * { @androidx.room.Entity *; }
-keepclassmembers class * { @androidx.room.Dao *; }
-keepclassmembers class * { @androidx.room.Database *; }
-keepclassmembers class * { @androidx.room.TypeConverter *; }
-keepclassmembers class * { @androidx.room.PrimaryKey *; }
-keepclassmembers class * { @androidx.room.Embedded *; }
-keepclassmembers class * { @androidx.room.Relation *; }

#------------- Google API, Drive, Sign-In ve GSON Kuralları (ÖNEMLİ) -------------
# Bu bölüm, Google Drive yedekleme özelliğinin release modunda çalışması için kritiktir.

# Google Play Services ve Google Sign-In
-keep class com.google.android.gms.auth.** { *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * { @com.google.android.gms.common.annotation.KeepName *; }

# Google API Client ve Drive API
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class * extends com.google.api.client.util.GenericData {
    public <init>();
}
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.drive.**

# GSON (JSON Serileştirme)
# GSON'un reflection ile kullandığı tüm alanları ve metodları korur.
-keepattributes Signature, *Annotation*
-keepclassmembers,allowshrinking class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

#------------- Proje Veri ve Model Sınıfları -------------
# Projenizdeki tüm sınıfların korunmasını sağlar. Bu, özellikle GSON ve Room için önemlidir.
-keep class com.codenzi.snapnote.** { *; }

#------------- Genel Kurallar -------------
# Parcelable arayüzünü uygulayan sınıfları ve CREATOR alanını korur.
-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum sınıflarını korur.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Gradle tarafından otomatik oluşturulan uyarıları bastır.
-dontwarn javax.naming.**
-dontwarn javax.servlet.**
-dontwarn org.apache.avalon.framework.logger.Logger
-dontwarn org.apache.log.Hierarchy
-dontwarn org.apache.log.Logger
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.ietf.jgss.**
-dontwarn org.joda.time.Instant