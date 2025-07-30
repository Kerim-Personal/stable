# Bu dosya, getDefaultProguardFile('proguard-android-optimize.txt') tarafından
# sağlanan varsayılan kurallara ek olarak kullanılır.

#------------- Kotlin Coroutines Kuralları -------------
# Coroutines'in dahili hata ayıklama mekanizmalarının çalışmasını sağlar.
-keep class kotlinx.coroutines.debug.internal.DebugProbesKt {
    <fields>;
    <methods>;
}

#------------- Hilt Kuralları -------------
# Hilt tarafından oluşturulan ve kullanılan sınıfların korunmasını sağlar.
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
# Room veritabanı varlık (entity) sınıflarını korur.
-keep class androidx.room.** { *; }
-keepclassmembers class * { @androidx.room.Entity *; }
-keepclassmembers class * { @androidx.room.Dao *; }
-keepclassmembers class * { @androidx.room.Database *; }
-keepclassmembers class * { @androidx.room.TypeConverter *; }
-keepclassmembers class * { @androidx.room.PrimaryKey *; }
-keepclassmembers class * { @androidx.room.Embedded *; }
-keepclassmembers class * { @androidx.room.Relation *; }

#------------- Google Play Hizmetleri ve API Kuralları -------------
# YENİ: Credential Manager API (Yeni Google Sign-In) için gerekli kural.
-keep class com.google.android.gms.auth.api.identity.** { *; }
-dontwarn com.google.android.gms.auth.api.identity.**

# Google API Client ve Drive API için gerekli kurallar (Mevcut hali doğruydu, korundu)
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-keepclassmembers class * extends com.google.api.client.util.GenericData {
    public <init>();
}

#------------- Gson (JSON işleme) için gerekli kurallar -------------
# Yedekleme ve geri yüklemede kullanılan veri sınıflarını korur.
-keep class com.codenzi.snapnote.BackupData { *; }
-keep class com.codenzi.snapnote.AppSettings { *; }
-keep class com.codenzi.snapnote.NoteContent { *; }
-keep class com.codenzi.snapnote.ChecklistItem { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowshrinking class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

#------------- Genel Kurallar -------------
# Parcelable arayüzünü uygulayan sınıfları korur.
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Enum sınıflarının `valueOf` metodunu korur.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

#------------- Gradle Tarafından Oluşturulan Uyarı Bastırmaları -------------
# Bu kurallar projenizde bir sorun olduğu anlamına gelmez, sadece ProGuard'ın
# bulamadığı ancak çalışma zamanında sorun çıkarmayan sınıflar için uyarıları gizler.
# (Mevcut hali doğruydu, korundu)
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn javax.servlet.ServletContextListener
-dontwarn org.apache.avalon.framework.logger.Logger
-dontwarn org.apache.log.Hierarchy
-dontwarn org.apache.log.Logger
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
-dontwarn org.joda.time.Instant